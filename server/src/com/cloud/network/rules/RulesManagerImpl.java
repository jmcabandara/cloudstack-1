/**
 *  Copyright (C) 2010 Cloud.com, Inc.  All rights reserved.
 * 
 * This software is licensed under the GNU General Public License v3 or later.
 * 
 * It is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 */
package com.cloud.network.rules;

import java.util.List;
import java.util.Map;

import javax.ejb.Local;
import javax.naming.ConfigurationException;

import org.apache.log4j.Logger;

import com.cloud.api.commands.ListIpForwardingRulesCmd;
import com.cloud.api.commands.ListPortForwardingRulesCmd;
import com.cloud.event.EventTypes;
import com.cloud.event.EventUtils;
import com.cloud.event.EventVO;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.NetworkRuleConflictException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.IPAddressVO;
import com.cloud.network.IpAddress;
import com.cloud.network.Network;
import com.cloud.network.NetworkManager;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.rules.FirewallRule.State;
import com.cloud.network.rules.dao.PortForwardingRulesDao;
import com.cloud.offering.NetworkOffering.GuestIpType;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.UserContext;
import com.cloud.uservm.UserVm;
import com.cloud.utils.component.Inject;
import com.cloud.utils.component.Manager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.Ip;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.Nic;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.dao.UserVmDao;

@Local(value=RulesManager.class)
public class RulesManagerImpl implements RulesManager, RulesService, Manager {
    private static final Logger s_logger = Logger.getLogger(RulesManagerImpl.class);
    String _name;
    
    @Inject PortForwardingRulesDao _forwardingDao;
    @Inject FirewallRulesDao _firewallDao;
    @Inject IPAddressDao _ipAddressDao;
    @Inject UserVmDao _vmDao;
    @Inject AccountManager _accountMgr;
    @Inject NetworkManager _networkMgr;
    

    @Override
    public void detectRulesConflict(FirewallRule newRule, IpAddress ipAddress) throws NetworkRuleConflictException {
        assert newRule.getSourceIpAddress().equals(ipAddress.getAddress()) : "You passed in an ip address that doesn't match the address in the new rule";
        
        List<FirewallRuleVO> rules = _firewallDao.listByIpAndNotRevoked(newRule.getSourceIpAddress());
        assert (rules.size() >= 1) : "For network rules, we now always first persist the rule and then check for network conflicts so we should at least have one rule at this point.";
        
        if (ipAddress.isOneToOneNat() && rules.size() > 1) {
            throw new NetworkRuleConflictException("There are already rules in existence for the " + newRule.getSourceIpAddress());
        }
        
        for (FirewallRuleVO rule : rules) {
            if (rule.getId() == newRule.getId()) {
                continue;  // Skips my own rule.
            }
            if (rule.getNetworkId() != newRule.getNetworkId() && rule.getState() != State.Revoke) {
                throw new NetworkRuleConflictException("New rule is for a different network than what's specified in rule " + rule.getXid());
            }
            if (rule.getProtocol().equals(NetUtils.NAT_PROTO)) {
                throw new NetworkRuleConflictException("There is already a one to one NAT specified for " + newRule.getSourceIpAddress());
            }
            if ((rule.getSourcePortStart() <= newRule.getSourcePortStart() && rule.getSourcePortEnd() >= newRule.getSourcePortStart()) || 
                (rule.getSourcePortStart() <= newRule.getSourcePortEnd() && rule.getSourcePortEnd() >= newRule.getSourcePortEnd()) ||
                (newRule.getSourcePortStart() <= rule.getSourcePortStart() && newRule.getSourcePortEnd() >= rule.getSourcePortStart()) ||
                (newRule.getSourcePortStart() <= rule.getSourcePortEnd() && newRule.getSourcePortEnd() >= rule.getSourcePortEnd())) {
                throw new NetworkRuleConflictException("The range specified, " + newRule.getSourcePortStart() + "-" + newRule.getSourcePortEnd() + ", conflicts with rule " + rule.getId() + " which has " + rule.getSourcePortStart() + "-" + rule.getSourcePortEnd());
            }
        }
        
        if (s_logger.isDebugEnabled()) { 
            s_logger.debug("No network rule conflicts detected for " + newRule + " against " + (rules.size() - 1) + " existing rules");
        }
        
    }

    @Override
    public void checkIpAndUserVm(IpAddress ipAddress, UserVm userVm, Account caller) throws InvalidParameterValueException, PermissionDeniedException {
        if (ipAddress == null || ipAddress.getAllocated() == null || ipAddress.getAccountId() == null) {
            throw new InvalidParameterValueException("Unable to create ip forwarding rule on address " + ipAddress + ", invalid IP address specified.");
        }
        
        if (userVm == null) {
            return;
        }
        
        _accountMgr.checkAccess(caller, userVm);
        
        // validate that IP address and userVM belong to the same account
        if (ipAddress.getAccountId().longValue() != userVm.getAccountId()) {
            throw new InvalidParameterValueException("Unable to create ip forwarding rule, IP address " + ipAddress + " owner is not the same as owner of virtual machine " + userVm.toString()); 
        }

        // validate that userVM is in the same availability zone as the IP address
        if (ipAddress.getDataCenterId() != userVm.getDataCenterId()) {
            throw new InvalidParameterValueException("Unable to create ip forwarding rule, IP address " + ipAddress + " is not in the same availability zone as virtual machine " + userVm.toString());
        }
    }

    @Override
    public PortForwardingRule createPortForwardingRule(PortForwardingRule rule, Long vmId, Account caller) throws NetworkRuleConflictException {
        String ipAddr = rule.getSourceIpAddress().addr();
        
        IPAddressVO ipAddress = _ipAddressDao.findById(ipAddr);
        
        Ip dstIp = rule.getDestinationIpAddress();
        long networkId = rule.getNetworkId();
        UserVmVO vm = null;
        Network network = null;
        if (vmId != null) {
            // validate user VM exists
            vm = _vmDao.findById(vmId);
            if (vm == null) {
                throw new InvalidParameterValueException("Unable to create ip forwarding rule on address " + ipAddress + ", invalid virtual machine id specified (" + vmId + ").");
            }
            
            dstIp = null;
            List<? extends Nic> nics = _networkMgr.getNics(vm);
            for (Nic nic : nics) {
                Network ntwk = _networkMgr.getNetwork(nic.getNetworkId());
                if (ntwk.getGuestType() == GuestIpType.Virtualized) {
                    network = ntwk;
                    dstIp = new Ip(nic.getIp4Address());
                    break;
                }
            }
            
            if (network == null) {
                throw new CloudRuntimeException("Unable to find ip address to map to in " + vmId);
            }
        } else {
            network = _networkMgr.getNetwork(rule.getNetworkId());
            if (network == null) {
                throw new InvalidParameterValueException("Unable to get the network " + rule.getNetworkId());
            }
        }
        
        networkId = network.getId();
        long accountId = network.getAccountId();
        long domainId = network.getDomainId();
        
        checkIpAndUserVm(ipAddress, vm, caller);
        
        PortForwardingRuleVO newRule = 
            new PortForwardingRuleVO(rule.getXid(), 
                    rule.getSourceIpAddress(), 
                    rule.getSourcePortStart(), 
                    rule.getSourcePortEnd(),
                    dstIp,
                    rule.getDestinationPortStart(), 
                    rule.getDestinationPortEnd(), 
                    rule.getProtocol(), 
                    networkId,
                    accountId,
                    domainId);
        newRule = _forwardingDao.persist(newRule);

        boolean success = false;
        try {
            detectRulesConflict(newRule, ipAddress);
            if (!_firewallDao.setStateToAdd(newRule)) {
                throw new CloudRuntimeException("Unable to update the state to add for " + newRule);
            }
            
            success = true;
            return newRule;
        } catch (Exception e) {
            _forwardingDao.remove(newRule.getId());
            if (e instanceof NetworkRuleConflictException) {
                throw (NetworkRuleConflictException)e;
            }
            
            throw new CloudRuntimeException("Unable to add rule for " + newRule.getSourceIpAddress(), e);
        } finally {
            // Save and create the event
            String description;
            String ruleName = "ip forwarding";
            String level = EventVO.LEVEL_INFO;

            if (success == true) {
                description = "created new " + ruleName + " rule [" + newRule.getSourceIpAddress() + ":" + newRule.getSourcePortStart() + "]->["
                + newRule.getDestinationIpAddress() + ":" + newRule.getDestinationPortStart() + "]" + " " + newRule.getProtocol();
            } else {
                level = EventVO.LEVEL_ERROR;
                description = "failed to create new " + ruleName + " rule [" + newRule.getSourceIpAddress() + ":" + newRule.getSourcePortStart() + "]->["
                + newRule.getDestinationIpAddress() + ":" + newRule.getDestinationPortStart() + "]" + " " + newRule.getProtocol();
            }

            EventUtils.saveEvent(UserContext.current().getUserId(), vm.getAccountId(), level, EventTypes.EVENT_NET_RULE_ADD, description);
        }
    }
    
    protected void revokeRule(FirewallRuleVO rule, Account caller) {
        _accountMgr.checkAccess(caller, rule);
        
        if (rule.getState() == State.Staged) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Found a rule that is still in stage state so just removing it: " + rule);
            }
            _firewallDao.remove(rule.getId());
            return;
        } else if (rule.getState() == State.Add) {
            rule.setState(State.Revoke);
            _firewallDao.update(rule.getId(), rule);
        }
    }
    
    @Override
    public PortForwardingRule revokePortForwardingRule(long ruleId, boolean apply, Account caller) {
        PortForwardingRuleVO rule = _forwardingDao.findById(ruleId);
        if (rule == null) {
            throw new InvalidParameterValueException("Unable to find " + ruleId);
        }
        
        revokeRule(rule, caller);
        if (apply) {
            applyPortForwardingRules(rule.getSourceIpAddress(), true);
        }
        return rule;
    }

    @Override
    public PortForwardingRule revokePortForwardingRule(String ruleId, Account caller) {
        // FIXME: Not working yet.
        return null;
    }

    @Override
    public List<? extends PortForwardingRule> listPortForwardingRules(ListPortForwardingRulesCmd cmd) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public PortForwardingRule createIpForwardingRuleInDb(String ipAddr, long virtualMachineId) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public PortForwardingRule createIpForwardingRuleOnDomr(long ruleId) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public boolean deleteIpForwardingRule(Long id) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean deletePortForwardingRule(Long id, boolean sysContext) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override 
    public boolean applyPortForwardingRules(Ip ip, boolean continueOnError) {
        try {
            return applyPortForwardingRules(ip, continueOnError, null);
        } catch (ResourceUnavailableException e) {
            s_logger.warn("Unable to reapply port forwarding rules for " + ip);
            return false;
        }
    }
    
    protected boolean applyPortForwardingRules(Ip ip, boolean continueOnError, Account caller) throws ResourceUnavailableException {
        List<PortForwardingRuleVO> rules = _forwardingDao.listForApplication(ip);
        if (rules.size() == 0) {
            s_logger.debug("There are no rules to apply for " + ip);
            return true;
        }

        if (caller != null) {
            _accountMgr.checkAccess(caller, rules.toArray(new PortForwardingRuleVO[rules.size()]));
        }
        
        return _networkMgr.applyRules(ip, rules, continueOnError);
    }
    
    @Override
    public List<PortForwardingRuleVO> searchForIpForwardingRules(ListIpForwardingRulesCmd cmd){
//        String ipAddress = cmd.getPublicIpAddress();
//        Filter searchFilter = new Filter(PortForwardingRuleVO.class, "id", true, cmd.getStartIndex(), cmd.getPageSizeVal());
//        SearchCriteria<PortForwardingRuleVO> sc = _firewallRulesDao.createSearchCriteria();
//
//        if (ipAddress != null) {
//            sc.addAnd("publicIpAddress", SearchCriteria.Op.EQ, ipAddress);
//        }
//        
//        //search for rules with protocol = nat
//        sc.addAnd("protocol", SearchCriteria.Op.EQ, NetUtils.NAT_PROTO);
//
//        return _firewallRulesDao.search(sc, searchFilter);
        return null;
    }
    
    
    
    @Override
    public boolean applyPortForwardingRules(Ip ip, Account caller) throws ResourceUnavailableException {
        return applyPortForwardingRules(ip, false, caller);
    }

    @Override
    public boolean applyNatRules(Ip ip, Account caller) throws ResourceUnavailableException {
        // TODO Auto-generated method stub
        return false;
    }
    
    @Override
    public boolean applyFirewallRules(Ip ip, Account caller) throws ResourceUnavailableException {
        // TODO Auto-generated method stub
        return false;
    }
    
    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        _name = name;
        return true;
    }

    @Override
    public boolean start() {
        return true;
    }

    @Override
    public boolean stop() {
        return true;
    }

    @Override
    public String getName() {
        return _name;
    }
//    @Override
//    public boolean updateFirewallRule(final PortForwardingRuleVO rule, String oldPrivateIP, String oldPrivatePort) {
//
//        final IPAddressVO ipVO = _ipAddressDao.findById(rule.getSourceIpAddress());
//        if (ipVO == null || ipVO.getAllocated() == null) {
//            return false;
//        }
//
//        final DomainRouterVO router = _routerMgr.getRouter(ipVO.getAccountId(), ipVO.getDataCenterId());
//        Long hostId = router.getHostId();
//        if (router == null || router.getHostId() == null) {
//            return true;
//        }
//
//        if (rule.isForwarding()) {
//            return updatePortForwardingRule(rule, router, hostId, oldPrivateIP, oldPrivatePort);
//        } else if (rule.getGroupId() != null) {
//            final List<PortForwardingRuleVO> fwRules = _rulesDao.listIPForwardingForLB(ipVO.getAccountId(), ipVO.getDataCenterId());
//
//            return updateLoadBalancerRules(fwRules, router, hostId);
//        }
//        return true;
//    }
//
//    @Override
//    public List<PortForwardingRuleVO> updateFirewallRules(final String publicIpAddress, final List<PortForwardingRuleVO> fwRules, final DomainRouterVO router) {
//        final List<PortForwardingRuleVO> result = new ArrayList<PortForwardingRuleVO>();
//        if (fwRules.size() == 0) {
//            return result;
//        }
//
//        if (router == null || router.getHostId() == null) {
//            return fwRules;
//        } else {
//            final HostVO host = _hostDao.findById(router.getHostId());
//            return updateFirewallRules(host, router.getInstanceName(), router.getPrivateIpAddress(), fwRules);
//        }
//    }
//
//    public List<PortForwardingRuleVO> updateFirewallRules(final HostVO host, final String routerName, final String routerIp, final List<PortForwardingRuleVO> fwRules) {
//        final List<PortForwardingRuleVO> result = new ArrayList<PortForwardingRuleVO>();
//        if (fwRules.size() == 0) {
//            s_logger.debug("There are no firewall rules");
//            return result;
//        }
//
//        Commands cmds = new Commands(OnError.Continue);
//        final List<PortForwardingRuleVO> lbRules = new ArrayList<PortForwardingRuleVO>();
//        final List<PortForwardingRuleVO> fwdRules = new ArrayList<PortForwardingRuleVO>();
//
//        int i=0;
//        for (PortForwardingRuleVO rule : fwRules) {
//            // Determine the VLAN ID and netmask of the rule's public IP address
//            IPAddressVO ip = _ipAddressDao.findById(rule.getSourceIpAddress());
//            VlanVO vlan = _vlanDao.findById(new Long(ip.getVlanDbId()));
//            String vlanNetmask = vlan.getVlanNetmask();
//            rule.setVlanNetmask(vlanNetmask);
//
//            if (rule.isForwarding()) {
//                fwdRules.add(rule);
//                final SetFirewallRuleCommand cmd = new SetFirewallRuleCommand(routerName, routerIp,rule, false);
//                cmds.addCommand(cmd);
//            } else if (rule.getGroupId() != null){
//                lbRules.add(rule);
//            }
//
//        }
//        if (lbRules.size() > 0) { //at least one load balancer rule
//            final LoadBalancerConfigurator cfgrtr = new HAProxyConfigurator();
//            final String [] cfg = cfgrtr.generateConfiguration(fwRules);
//            final String [][] addRemoveRules = cfgrtr.generateFwRules(fwRules);
//            final LoadBalancerCfgCommand cmd = new LoadBalancerCfgCommand(cfg, addRemoveRules, routerName, routerIp);
//            cmds.addCommand(cmd);
//        }
//        if (cmds.size() == 0) {
//            return result;
//        }
//        Answer [] answers = null;
//        try {
//            answers = _agentMgr.send(host.getId(), cmds);
//        } catch (final AgentUnavailableException e) {
//            s_logger.warn("agent unavailable", e);
//        } catch (final OperationTimedoutException e) {
//            s_logger.warn("Timed Out", e);
//        }
//        if (answers == null ){
//            return result;
//        }
//        i=0;
//        for (final PortForwardingRuleVO rule:fwdRules){
//            final Answer ans = answers[i++];
//            if (ans != null) {
//                if (ans.getResult()) {
//                    result.add(rule);
//                } else {
//                    s_logger.warn("Unable to update firewall rule: " + rule.toString());
//                }
//            }
//        }
//        if (i == (answers.length-1)) {
//            final Answer lbAnswer = answers[i];
//            if (lbAnswer.getResult()) {
//                result.addAll(lbRules);
//            } else {
//                s_logger.warn("Unable to update lb rules.");
//            }
//        }
//        return result;
//    }
//
//    private boolean updatePortForwardingRule(final PortForwardingRuleVO rule, final DomainRouterVO router, Long hostId, String oldPrivateIP, String oldPrivatePort) {
//        IPAddressVO ip = _ipAddressDao.findById(rule.getSourceIpAddress());
//        VlanVO vlan = _vlanDao.findById(new Long(ip.getVlanDbId()));
//        rule.setVlanNetmask(vlan.getVlanNetmask());
//
//        final SetFirewallRuleCommand cmd = new SetFirewallRuleCommand(router.getInstanceName(), router.getPrivateIpAddress(), rule, oldPrivateIP, oldPrivatePort);
//        final Answer ans = _agentMgr.easySend(hostId, cmd);
//        if (ans == null) {
//            return false;
//        } else {
//            return ans.getResult();
//        }
//    }
//
//    @Override
//    public List<PortForwardingRuleVO>  updatePortForwardingRules(final List<PortForwardingRuleVO> fwRules, final DomainRouterVO router, Long hostId ){
//        final List<PortForwardingRuleVO> fwdRules = new ArrayList<PortForwardingRuleVO>();
//        final List<PortForwardingRuleVO> result = new ArrayList<PortForwardingRuleVO>();
//
//        if (fwRules.size() == 0) {
//            return result;
//        }
//
//        Commands cmds = new Commands(OnError.Continue);
//        int i=0;
//        for (final PortForwardingRuleVO rule: fwRules) {
//            IPAddressVO ip = _ipAddressDao.findById(rule.getSourceIpAddress());
//            VlanVO vlan = _vlanDao.findById(new Long(ip.getVlanDbId()));
//            String vlanNetmask = vlan.getVlanNetmask();
//            rule.setVlanNetmask(vlanNetmask);
//            if (rule.isForwarding()) {
//                fwdRules.add(rule);
//                final SetFirewallRuleCommand cmd = new SetFirewallRuleCommand(router.getInstanceName(), router.getPrivateIpAddress(),rule, false);
//                cmds.addCommand(cmd);
//            }
//        }
//        try {
//            _agentMgr.send(hostId, cmds);
//        } catch (final AgentUnavailableException e) {
//            s_logger.warn("agent unavailable", e);
//        } catch (final OperationTimedoutException e) {
//            s_logger.warn("Timed Out", e);
//        }
//        Answer[] answers = cmds.getAnswers();
//        if (answers == null ){
//            return result;
//        }
//        i=0;
//        for (final PortForwardingRuleVO rule:fwdRules){
//            final Answer ans = answers[i++];
//            if (ans != null) {
//                if (ans.getResult()) {
//                    result.add(rule);
//                }
//            }
//        }
//        return result;
//    }
//
//    @Override
//    public PortForwardingRuleVO createPortForwardingRule(CreatePortForwardingRuleCmd cmd) throws InvalidParameterValueException, PermissionDeniedException, NetworkRuleConflictException {
//        // validate IP Address exists
//        IPAddressVO ipAddress = _ipAddressDao.findById(cmd.getIpAddress());
//        if (ipAddress == null) {
//            throw new InvalidParameterValueException("Unable to create port forwarding rule on address " + ipAddress + ", invalid IP address specified.");
//        }
//
//        // validate user VM exists
//        UserVmVO userVM = _vmDao.findById(cmd.getVirtualMachineId());
//        if (userVM == null) {
//            throw new InvalidParameterValueException("Unable to create port forwarding rule on address " + ipAddress + ", invalid virtual machine id specified (" + cmd.getVirtualMachineId() + ").");
//        }
//
//        // validate that IP address and userVM belong to the same account
//        if ((ipAddress.getAccountId() == null) || (ipAddress.getAccountId().longValue() != userVM.getAccountId())) {
//            throw new InvalidParameterValueException("Unable to create port forwarding rule, IP address " + ipAddress + " owner is not the same as owner of virtual machine " + userVM.toString()); 
//        }
//
//        // validate that userVM is in the same availability zone as the IP address
//        if (ipAddress.getDataCenterId() != userVM.getDataCenterId()) {
//            throw new InvalidParameterValueException("Unable to create port forwarding rule, IP address " + ipAddress + " is not in the same availability zone as virtual machine " + userVM.toString());
//        }
//
//        // if an admin account was passed in, or no account was passed in, make sure we honor the accountName/domainId parameters
//        Account account = UserContext.current().getAccount();
//        if (account != null) {
//            if ((account.getType() == Account.ACCOUNT_TYPE_ADMIN) || (account.getType() == Account.ACCOUNT_TYPE_DOMAIN_ADMIN)) {
//                if (!_domainDao.isChildDomain(account.getDomainId(), userVM.getDomainId())) {
//                    throw new PermissionDeniedException("Unable to create port forwarding rule, IP address " + ipAddress + " to virtual machine " + cmd.getVirtualMachineId() + ", permission denied.");
//                }
//            } else if (account.getId() != userVM.getAccountId()) {
//                throw new PermissionDeniedException("Unable to create port forwarding rule, IP address " + ipAddress + " to virtual machine " + cmd.getVirtualMachineId() + ", permission denied.");
//            }
//        }
//
//        // set up some local variables
//        String protocol = cmd.getProtocol();
//        String publicPort = cmd.getPublicPort();
//        String privatePort = cmd.getPrivatePort();
//
//        // sanity check that the vm can be applied to the load balancer
//        ServiceOfferingVO offering = _serviceOfferingDao.findById(userVM.getServiceOfferingId());
//        if ((offering == null) || !GuestIpType.Virtualized.equals(offering.getGuestIpType())) {
//            if (s_logger.isDebugEnabled()) {
//                s_logger.debug("Unable to create port forwarding rule (" + protocol + ":" + publicPort + "->" + privatePort + ") for virtual machine " + userVM.toString() + ", bad network type (" + ((offering == null) ? "null" : offering.getGuestIpType()) + ")");
//            }
//
//            throw new IllegalArgumentException("Unable to create port forwarding rule (" + protocol + ":" + publicPort + "->" + privatePort + ") for virtual machine " + userVM.toString() + ", bad network type (" + ((offering == null) ? "null" : offering.getGuestIpType()) + ")");
//        }
//
//        // check for ip address/port conflicts by checking existing forwarding and load balancing rules
//        List<PortForwardingRuleVO> existingRulesOnPubIp = _rulesDao.listIPForwarding(ipAddress.getAddress());
//
//        // FIXME:  The mapped ports should be String, String, List<String> since more than one proto can be mapped...
//        Map<String, Ternary<String, String, List<String>>> mappedPublicPorts = new HashMap<String, Ternary<String, String, List<String>>>();
//
//        if (existingRulesOnPubIp != null) {
//            for (PortForwardingRuleVO fwRule : existingRulesOnPubIp) {
//                Ternary<String, String, List<String>> portMappings = mappedPublicPorts.get(fwRule.getSourcePort());
//                List<String> protocolList = null;
//                if (portMappings == null) {
//                    protocolList = new ArrayList<String>();
//                } else {
//                    protocolList = portMappings.third();
//                }
//                protocolList.add(fwRule.getProtocol());
//                mappedPublicPorts.put(fwRule.getSourcePort(), new Ternary<String, String, List<String>>(fwRule.getDestinationIpAddress(), fwRule.getDestinationPort(), protocolList));
//            }
//        }
//
//        Ternary<String, String, List<String>> privateIpPort = mappedPublicPorts.get(publicPort);
//        if (privateIpPort != null) {
//            if (privateIpPort.first().equals(userVM.getGuestIpAddress()) && privateIpPort.second().equals(privatePort)) {
//                List<String> protocolList = privateIpPort.third();
//                for (String mappedProtocol : protocolList) {
//                    if (mappedProtocol.equalsIgnoreCase(protocol)) {
//                        if (s_logger.isDebugEnabled()) {
//                            s_logger.debug("skipping the creating of firewall rule " + ipAddress + ":" + publicPort + " to " + userVM.getGuestIpAddress() + ":" + privatePort + "; rule already exists.");
//                        }
//                        // already mapped
//                        throw new NetworkRuleConflictException("An existing port forwarding service rule for " + ipAddress + ":" + publicPort
//                                + " already exists, found while trying to create mapping to " + userVM.getGuestIpAddress() + ":" + privatePort + ".");
//                    }
//                }
//            } else {
//                // FIXME:  Will we need to refactor this for both assign port forwarding service and create port forwarding rule?
//                //                throw new NetworkRuleConflictException("An existing port forwarding service rule for " + ipAddress + ":" + publicPort
//                //                        + " already exists, found while trying to create mapping to " + userVM.getGuestIpAddress() + ":" + privatePort + ((securityGroupId == null) ? "." : " from port forwarding service "
//                //                        + securityGroupId.toString() + "."));
//                throw new NetworkRuleConflictException("An existing port forwarding service rule for " + ipAddress + ":" + publicPort
//                        + " already exists, found while trying to create mapping to " + userVM.getGuestIpAddress() + ":" + privatePort + ".");
//            }
//        }
//
//        PortForwardingRuleVO newFwRule = new PortForwardingRuleVO();
//        newFwRule.setEnabled(true);
//        newFwRule.setForwarding(true);
//        newFwRule.setPrivatePort(privatePort);
//        newFwRule.setProtocol(protocol);
//        newFwRule.setPublicPort(publicPort);
//        newFwRule.setPublicIpAddress(ipAddress.getAddress());
//        newFwRule.setPrivateIpAddress(userVM.getGuestIpAddress());
//        //        newFwRule.setGroupId(securityGroupId);
//        newFwRule.setGroupId(null);
//
//        // In 1.0 the rules were always persisted when a user created a rule.  When the rules get sent down
//        // the stopOnError parameter is set to false, so the agent will apply all rules that it can.  That
//        // behavior is preserved here by persisting the rule before sending it to the agent.
//        _rulesDao.persist(newFwRule);
//
//        boolean success = updateFirewallRule(newFwRule, null, null);
//
//        // Save and create the event
//        String description;
//        String ruleName = "ip forwarding";
//        String level = EventVO.LEVEL_INFO;
//
//        if (success == true) {
//            description = "created new " + ruleName + " rule [" + newFwRule.getSourceIpAddress() + ":" + newFwRule.getSourcePort() + "]->["
//            + newFwRule.getDestinationIpAddress() + ":" + newFwRule.getDestinationPort() + "]" + " " + newFwRule.getProtocol();
//        } else {
//            level = EventVO.LEVEL_ERROR;
//            description = "failed to create new " + ruleName + " rule [" + newFwRule.getSourceIpAddress() + ":" + newFwRule.getSourcePort() + "]->["
//            + newFwRule.getDestinationIpAddress() + ":" + newFwRule.getDestinationPort() + "]" + " " + newFwRule.getProtocol();
//        }
//
//        EventUtils.saveEvent(UserContext.current().getUserId(), userVM.getAccountId(), level, EventTypes.EVENT_NET_RULE_ADD, description);
//
//        return newFwRule;
//    }
//
//    @Override
//    public List<PortForwardingRuleVO> listPortForwardingRules(ListPortForwardingRulesCmd cmd) throws InvalidParameterValueException, PermissionDeniedException {
//        String ipAddress = cmd.getIpAddress();
//        Account account = UserContext.current().getAccount();
//
//        IPAddressVO ipAddressVO = _ipAddressDao.findById(ipAddress);
//        if (ipAddressVO == null) {
//            throw new InvalidParameterValueException("Unable to find IP address " + ipAddress);
//        }
//
//        Account addrOwner = _accountDao.findById(ipAddressVO.getAccountId());
//
//        // if an admin account was passed in, or no account was passed in, make sure we honor the accountName/domainId parameters
//        if ((account != null) && isAdmin(account.getType())) {
//            if (ipAddressVO.getAccountId() != null) {
//                if ((addrOwner != null) && !_domainDao.isChildDomain(account.getDomainId(), addrOwner.getDomainId())) {
//                    throw new PermissionDeniedException("Unable to list port forwarding rules for address " + ipAddress + ", permission denied for account " + account.getId());
//                }
//            } 
//        } else {
//            if (account != null) {
//                if ((ipAddressVO.getAccountId() == null) || (account.getId() != ipAddressVO.getAccountId().longValue())) {
//                    throw new PermissionDeniedException("Unable to list port forwarding rules for address " + ipAddress + ", permission denied for account " + account.getId());
//                }
//            }
//        }
//
//        return _rulesDao.listIPForwarding(cmd.getIpAddress(), true);
//    }
    
//  @Override @DB
//  public boolean deletePortForwardingRule(Long id, boolean sysContext) {
//      Long ruleId = id;
//      Long userId = null;
//      Account account = null;
//      if(sysContext){
//          userId = User.UID_SYSTEM;
//          account = _accountDao.findById(User.UID_SYSTEM);
//      }else{
//          userId = UserContext.current().getUserId();
//          account = UserContext.current().getAccount();         
//      }
//
//
//      //verify input parameters here
//      PortForwardingRuleVO rule = _firewallRulesDao.findById(ruleId);
//      if (rule == null) {
//          throw new InvalidParameterValueException("Unable to find port forwarding rule " + ruleId);
//      }
//
//      String publicIp = rule.getSourceIpAddress();
//      String privateIp = rule.getDestinationIpAddress();
//
//      IPAddressVO ipAddress = _ipAddressDao.findById(publicIp);
//      if (ipAddress == null) {
//          throw new InvalidParameterValueException("Unable to find IP address for port forwarding rule " + ruleId);
//      }
//
//      // although we are not writing these values to the DB, we will check
//      // them out of an abundance
//      // of caution (may not be warranted)
//      String privatePort = rule.getDestinationPort();
//      String publicPort = rule.getSourcePort();
//      if (!NetUtils.isValidPort(publicPort) || !NetUtils.isValidPort(privatePort)) {
//          throw new InvalidParameterValueException("Invalid value for port");
//      }
//
//      String proto = rule.getProtocol();
//      if (!NetUtils.isValidProto(proto)) {
//          throw new InvalidParameterValueException("Invalid protocol");
//      }
//
//      Account ruleOwner = _accountDao.findById(ipAddress.getAccountId());
//      if (ruleOwner == null) {
//          throw new InvalidParameterValueException("Unable to find owning account for port forwarding rule " + ruleId);
//      }
//
//      // if an admin account was passed in, or no account was passed in, make sure we honor the accountName/domainId parameters
//      if (account != null) {
//          if (isAdmin(account.getType())) {
//              if (!_domainDao.isChildDomain(account.getDomainId(), ruleOwner.getDomainId())) {
//                  throw new PermissionDeniedException("Unable to delete port forwarding rule " + ruleId + ", permission denied.");
//              }
//          } else if (account.getId() != ruleOwner.getId()) {
//              throw new PermissionDeniedException("Unable to delete port forwarding rule " + ruleId + ", permission denied.");
//          }
//      }
//
//      Transaction txn = Transaction.currentTxn();
//      boolean locked = false;
//      boolean success = false;
//      try {
//
//          IPAddressVO ipVO = _ipAddressDao.acquireInLockTable(publicIp);
//          if (ipVO == null) {
//              // throw this exception because hackers can use the api to probe for allocated ips
//              throw new PermissionDeniedException("User does not own supplied address");
//          }
//
//          locked = true;
//          txn.start();
//          List<PortForwardingRuleVO> fwdings = _firewallRulesDao.listIPForwardingForUpdate(publicIp, publicPort, proto);
//          PortForwardingRuleVO fwRule = null;
//          if (fwdings.size() == 0) {
//              throw new InvalidParameterValueException("No such rule");
//          } else if (fwdings.size() == 1) {
//              fwRule = fwdings.get(0);
//              if (fwRule.getDestinationIpAddress().equalsIgnoreCase(privateIp) && fwRule.getDestinationPort().equals(privatePort)) {
//                  _firewallRulesDao.expunge(fwRule.getId());
//              } else {
//                  throw new InvalidParameterValueException("No such rule");
//              }
//          } else {
//              throw new CloudRuntimeException("Multiple matches. Please contact support");
//          }
//          fwRule.setEnabled(false);
//          success = updateFirewallRule(fwRule, null, null);
//
//          String description;
//          String type = EventTypes.EVENT_NET_RULE_DELETE;
//          String level = EventVO.LEVEL_INFO;
//          String ruleName = rule.isForwarding() ? "ip forwarding" : "load balancer";
//
//          if (success) {
//              description = "deleted " + ruleName + " rule [" + publicIp + ":" + rule.getSourcePort() + "]->[" + rule.getDestinationIpAddress() + ":"
//              + rule.getDestinationPort() + "] " + rule.getProtocol();
//          } else {
//              level = EventVO.LEVEL_ERROR;
//              description = "Error while deleting " + ruleName + " rule [" + publicIp + ":" + rule.getSourcePort() + "]->[" + rule.getDestinationIpAddress() + ":"
//              + rule.getDestinationPort() + "] " + rule.getProtocol();
//          }
//          EventUtils.saveEvent(userId, ipAddress.getAccountId(), level, type, description);
//          txn.commit();
//      }catch (Exception ex) {
//          txn.rollback();
//          s_logger.error("Unexpected exception deleting port forwarding rule " + ruleId, ex);
//          return false;
//      }finally {
//          if (locked) {
//              _ipAddressDao.releaseFromLockTable(publicIp);
//          }
//          txn.close();
//      }
//      return success;
//  }
//    @Override @DB
//    public PortForwardingRule createIpForwardingRuleOnDomr(long ruleId) {
//        Transaction txn = Transaction.currentTxn();
//        txn.start();
//        boolean success = false;
//        PortForwardingRuleVO rule = null;
//        IPAddressVO ipAddress = null;
//        boolean locked = false;
//        try {
//            //get the rule 
//            rule = _rulesDao.findById(ruleId);
//
//            if(rule == null){
//                throw new PermissionDeniedException("Cannot create ip forwarding rule in db");
//            }
//
//            //get ip address 
//            ipAddress = _ipAddressDao.findById(rule.getSourceIpAddress());
//            if (ipAddress == null) {
//                throw new InvalidParameterValueException("Unable to create ip forwarding rule on address " + ipAddress + ", invalid IP address specified.");
//            }
//
//            //sync point
//            ipAddress = _ipAddressDao.acquireInLockTable(ipAddress.getAddress());
//
//            if(ipAddress == null){
//                s_logger.warn("Unable to acquire lock on ipAddress for creating 1-1 NAT rule");
//                return rule;
//            }else{
//                locked = true;
//            }
//
//            //get the domain router object
//            DomainRouterVO router = _routerMgr.getRouter(ipAddress.getAccountId(), ipAddress.getDataCenterId());
//            success = createOrDeleteIpForwardingRuleOnDomr(rule,router,rule.getDestinationIpAddress(),true); //true +> create
//
//            if(!success){
//                //corner case; delete record from db as domR rule creation failed
//                _rulesDao.remove(ruleId);
//                throw new PermissionDeniedException("Cannot create ip forwarding rule on domr, hence deleting created record in db");
//            }
//
//            //update the user_ip_address record
//            ipAddress.setOneToOneNat(true);
//            _ipAddressDao.update(ipAddress.getAddress(),ipAddress);
//
//            // Save and create the event
//            String description;
//            String ruleName = "ip forwarding";
//            String level = EventVO.LEVEL_INFO;
//
//            description = "created new " + ruleName + " rule [" + rule.getSourceIpAddress() + "]->["
//            + rule.getDestinationIpAddress() + "]" + ":" + rule.getProtocol();
//
//            EventUtils.saveEvent(UserContext.current().getUserId(), ipAddress.getAccountId(), level, EventTypes.EVENT_NET_RULE_ADD, description);
//            txn.commit();
//        } catch (Exception e) {
//            txn.rollback();
//            throw new ServerApiException(BaseCmd.INTERNAL_ERROR, e.getMessage());
//        }finally{
//            if(locked){
//                _ipAddressDao.releaseFromLockTable(ipAddress.getAddress());
//            }
//        }
//        return rule;
//    }
//
//    @Override @DB
//    public PortForwardingRule createIpForwardingRuleInDb(String ipAddr, long virtualMachineId) {
//
//        Transaction txn = Transaction.currentTxn();
//        txn.start();
//        UserVmVO userVM = null;
//        PortForwardingRuleVO newFwRule = null;
//        boolean locked = false;
//        try {
//            // validate IP Address exists
//            IPAddressVO ipAddress = _ipAddressDao.findById(ipAddr);
//            if (ipAddress == null) {
//                throw new InvalidParameterValueException("Unable to create ip forwarding rule on address " + ipAddress + ", invalid IP address specified.");
//            }
//
//            // validate user VM exists
//            userVM = _vmDao.findById(virtualMachineId);
//            if (userVM == null) {
//                throw new InvalidParameterValueException("Unable to create ip forwarding rule on address " + ipAddress + ", invalid virtual machine id specified (" + virtualMachineId + ").");
//            }
//
//            //sync point; cannot lock on rule ; hence sync on vm
//            userVM = _vmDao.acquireInLockTable(userVM.getId());
//
//            if(userVM == null){
//                s_logger.warn("Unable to acquire lock on user vm for creating 1-1 NAT rule");
//                return newFwRule;
//            }else{
//                locked = true;
//            }
//
//            // validate that IP address and userVM belong to the same account
//            if ((ipAddress.getAccountId() == null) || (ipAddress.getAccountId().longValue() != userVM.getAccountId())) {
//                throw new InvalidParameterValueException("Unable to create ip forwarding rule, IP address " + ipAddress + " owner is not the same as owner of virtual machine " + userVM.toString()); 
//            }
//
//            // validate that userVM is in the same availability zone as the IP address
//            if (ipAddress.getDataCenterId() != userVM.getDataCenterId()) {
//                throw new InvalidParameterValueException("Unable to create ip forwarding rule, IP address " + ipAddress + " is not in the same availability zone as virtual machine " + userVM.toString());
//            }
//
//            // if an admin account was passed in, or no account was passed in, make sure we honor the accountName/domainId parameters
//            Account account = UserContext.current().getAccount();
//            if (account != null) {
//                if ((account.getType() == Account.ACCOUNT_TYPE_ADMIN) || (account.getType() == Account.ACCOUNT_TYPE_DOMAIN_ADMIN)) {
//                    if (!_domainDao.isChildDomain(account.getDomainId(), userVM.getDomainId())) {
//                        throw new PermissionDeniedException("Unable to create ip forwarding rule, IP address " + ipAddress + " to virtual machine " + virtualMachineId + ", permission denied.");
//                    }
//                } else if (account.getId() != userVM.getAccountId()) {
//                    throw new PermissionDeniedException("Unable to create ip forwarding rule, IP address " + ipAddress + " to virtual machine " + virtualMachineId + ", permission denied.");
//                }
//            }
//
//            // check for ip address/port conflicts by checking existing forwarding and load balancing rules
//            List<PortForwardingRuleVO> existingNatRules = _rulesDao.findByPublicIpPrivateIpForNatRule(ipAddr, userVM.getGuestIpAddress());
//
//            if(existingNatRules.size() > 0){
//                throw new NetworkRuleConflictException("The specified rule for public ip:"+ipAddr+" vm id:"+virtualMachineId+" already exists");
//            }
//
//            //if given ip address is already source nat, return error
//            if(ipAddress.isSourceNat()){
//                throw new PermissionDeniedException("Cannot create a static nat rule for the ip:"+ipAddress.getAddress()+" ,this is already a source nat ip address");
//            }
//
//            //if given ip address is already static nat, return error
//            if(ipAddress.isOneToOneNat()){
//                throw new PermissionDeniedException("Cannot create a static nat rule for the ip:"+ipAddress.getAddress()+" ,this is already a static nat ip address");
//            }
//            
//            newFwRule = new PortForwardingRuleVO();
//            newFwRule.setEnabled(true);
//            newFwRule.setForwarding(true);
//            newFwRule.setPrivatePort(null);
//            newFwRule.setProtocol(NetUtils.NAT_PROTO);//protocol cannot be null; adding this as a NAT
//            newFwRule.setPublicPort(null);
//            newFwRule.setPublicIpAddress(ipAddress.getAddress());
//            newFwRule.setPrivateIpAddress(userVM.getGuestIpAddress());
//            newFwRule.setGroupId(null);
//
//            _rulesDao.persist(newFwRule);           
//            txn.commit();
//        } catch (Exception e) {
//            s_logger.warn("Unable to create new firewall rule for 1:1 NAT");
//            txn.rollback();
//            throw new ServerApiException(BaseCmd.INTERNAL_ERROR,"Unable to create new firewall rule for 1:1 NAT:"+e.getMessage());
//        }finally{
//            if(locked) {
//                _vmDao.releaseFromLockTable(userVM.getId());
//            }
//        }
//
//        return newFwRule;
//    }
//
//    @Override @DB
//    public boolean deleteIpForwardingRule(Long id) {
//        Long ruleId = id;
//        Long userId = UserContext.current().getUserId();
//        Account account = UserContext.current().getAccount();
//
//        //verify input parameters here
//        PortForwardingRuleVO rule = _firewallRulesDao.findById(ruleId);
//        if (rule == null) {
//            throw new InvalidParameterValueException("Unable to find port forwarding rule " + ruleId);
//        }
//
//        String publicIp = rule.getSourceIpAddress();
//
//
//        IPAddressVO ipAddress = _ipAddressDao.findById(publicIp);
//        if (ipAddress == null) {
//            throw new InvalidParameterValueException("Unable to find IP address for ip forwarding rule " + ruleId);
//        }
//
//        // although we are not writing these values to the DB, we will check
//        // them out of an abundance
//        // of caution (may not be warranted)
//
//        Account ruleOwner = _accountDao.findById(ipAddress.getAccountId());
//        if (ruleOwner == null) {
//            throw new InvalidParameterValueException("Unable to find owning account for ip forwarding rule " + ruleId);
//        }
//
//        // if an admin account was passed in, or no account was passed in, make sure we honor the accountName/domainId parameters
//        if (account != null) {
//            if (isAdmin(account.getType())) {
//                if (!_domainDao.isChildDomain(account.getDomainId(), ruleOwner.getDomainId())) {
//                    throw new PermissionDeniedException("Unable to delete ip forwarding rule " + ruleId + ", permission denied.");
//                }
//            } else if (account.getId() != ruleOwner.getId()) {
//                throw new PermissionDeniedException("Unable to delete ip forwarding rule " + ruleId + ", permission denied.");
//            }
//        }
//
//        Transaction txn = Transaction.currentTxn();
//        boolean locked = false;
//        boolean success = false;
//        try {
//
//            ipAddress = _ipAddressDao.acquireInLockTable(publicIp);
//            if (ipAddress == null) {
//                throw new PermissionDeniedException("Unable to obtain lock on record for deletion");
//            }
//
//            locked = true;
//            txn.start();
//
//            final DomainRouterVO router = _routerMgr.getRouter(ipAddress.getAccountId(), ipAddress.getDataCenterId());
//            success = createOrDeleteIpForwardingRuleOnDomr(rule, router, rule.getDestinationIpAddress(), false);
//            _firewallRulesDao.remove(ruleId);
//
//            //update the ip_address record
//            ipAddress.setOneToOneNat(false);
//            _ipAddressDao.persist(ipAddress);
//
//            String description;
//            String type = EventTypes.EVENT_NET_RULE_DELETE;
//            String level = EventVO.LEVEL_INFO;
//            String ruleName = rule.isForwarding() ? "ip forwarding" : "load balancer";
//
//            if (success) {
//                description = "deleted " + ruleName + " rule [" + publicIp +"]->[" + rule.getDestinationIpAddress() + "] " + rule.getProtocol();
//            } else {
//                level = EventVO.LEVEL_ERROR;
//                description = "Error while deleting " + ruleName + " rule [" + publicIp + "]->[" + rule.getDestinationIpAddress() +"] " + rule.getProtocol();
//            }
//            EventUtils.saveEvent(userId, ipAddress.getAccountId(), level, type, description);
//            txn.commit();
//        }catch (Exception ex) {
//            txn.rollback();
//            s_logger.error("Unexpected exception deleting port forwarding rule " + ruleId, ex);
//            return false;
//        }finally {
//            if (locked) {
//                _ipAddressDao.releaseFromLockTable(publicIp);
//            }
//            txn.close();
//        }
//        return success;
//    }
//
//    private boolean  createOrDeleteIpForwardingRuleOnDomr(PortForwardingRuleVO fwRule, DomainRouterVO router, String guestIp, boolean create){
//
//        Commands cmds = new Commands(OnError.Continue);
//        final SetFirewallRuleCommand cmd = new SetFirewallRuleCommand(router.getInstanceName(), router.getPrivateIpAddress(),fwRule, create);
//        cmds.addCommand(cmd);       
//        try {
//            _agentMgr.send(router.getHostId(), cmds);
//        } catch (final AgentUnavailableException e) {
//            s_logger.warn("agent unavailable", e);
//        } catch (final OperationTimedoutException e) {
//            s_logger.warn("Timed Out", e);
//        }
//        Answer[] answers = cmds.getAnswers();
//        if (answers == null || answers[0].getResult() == false ){
//            return false;
//        }else{
//            return true;
//        }
//    }
//    @Override
//    public PortForwardingRuleVO updatePortForwardingRule(UpdatePortForwardingRuleCmd cmd) throws InvalidParameterValueException, PermissionDeniedException{
//        String publicIp = cmd.getPublicIp();
//        String privateIp = cmd.getPrivateIp();
//        String privatePort = cmd.getPrivatePort();
//        String publicPort = cmd.getPublicPort();
//        String protocol = cmd.getProtocol();
//        Long vmId = cmd.getVirtualMachineId();
//        Long userId = UserContext.current().getUserId();
//        Account account = UserContext.current().getAccount();
//        UserVmVO userVM = null;
//        
//        if (userId == null) {
//            userId = Long.valueOf(User.UID_SYSTEM);
//        }
//
//        IPAddressVO ipAddressVO = findIPAddressById(publicIp);
//        if (ipAddressVO == null) {
//            throw new InvalidParameterValueException("Unable to find IP address " + publicIp);
//        }
//
//        if (ipAddressVO.getAccountId() == null) {
//            throw new InvalidParameterValueException("Unable to update port forwarding rule, owner of IP address " + publicIp + " not found.");
//        }
//
//        if (privateIp != null) {
//            if (!NetUtils.isValidIp(privateIp)) {
//                throw new InvalidParameterValueException("Invalid private IP address specified: " + privateIp);
//            }
//            Criteria c = new Criteria();
//            c.addCriteria(Criteria.ACCOUNTID, new Object[] {ipAddressVO.getAccountId()});
//            c.addCriteria(Criteria.DATACENTERID, ipAddressVO.getDataCenterId());
//            c.addCriteria(Criteria.IPADDRESS, privateIp);
//            List<UserVmVO> userVMs = searchForUserVMs(c);
//            if ((userVMs == null) || userVMs.isEmpty()) {
//                throw new ServerApiException(BaseCmd.PARAM_ERROR, "Invalid private IP address specified: " + privateIp + ", no virtual machine instances running with that address.");
//            }
//            userVM = userVMs.get(0);
//        } else if (vmId != null) {
//            userVM = findUserVMInstanceById(vmId);
//            if (userVM == null) {
//                throw new InvalidParameterValueException("Unable to find virtual machine with id " + vmId);
//            }
//
//            if ((ipAddressVO.getAccountId() == null) || (ipAddressVO.getAccountId().longValue() != userVM.getAccountId())) {
//                throw new PermissionDeniedException("Unable to update port forwarding rule on IP address " + publicIp + ", permission denied."); 
//            }
//
//            if (ipAddressVO.getDataCenterId() != userVM.getDataCenterId()) {
//                throw new PermissionDeniedException("Unable to update port forwarding rule, IP address " + publicIp + " is not in the same availability zone as virtual machine " + userVM.toString());
//            }
//
//            privateIp = userVM.getGuestIpAddress();
//        } else {
//            throw new InvalidParameterValueException("No private IP address (privateip) or virtual machine instance id (virtualmachineid) specified, unable to update port forwarding rule");
//        }
//
//        // if an admin account was passed in, or no account was passed in, make sure we honor the accountName/domainId parameters
//        if (account != null) {
//            if (isAdmin(account.getType())) {
//                if (!_domainDao.isChildDomain(account.getDomainId(), ipAddressVO.getDomainId())) {
//                    throw new PermissionDeniedException("Unable to update port forwarding rule on IP address " + publicIp + ", permission denied.");
//                }
//            } else if (account.getId() != ipAddressVO.getAccountId()) {
//                throw new PermissionDeniedException("Unable to update port forwarding rule on IP address " + publicIp + ", permission denied.");
//            }
//        }
//        
//        List<PortForwardingRuleVO> fwRules = _firewallRulesDao.listIPForwardingForUpdate(publicIp, publicPort, protocol);
//        if ((fwRules != null) && (fwRules.size() == 1)) {
//            PortForwardingRuleVO fwRule = fwRules.get(0);
//            String oldPrivateIP = fwRule.getDestinationIpAddress();
//            String oldPrivatePort = fwRule.getDestinationPort();
//            fwRule.setPrivateIpAddress(privateIp);
//            fwRule.setPrivatePort(privatePort);
//            _firewallRulesDao.update(fwRule.getId(), fwRule);
//            _networkMgr.updateFirewallRule(fwRule, oldPrivateIP, oldPrivatePort);
//            return fwRule;
//        }else{
//            s_logger.warn("Unable to find the rule to be updated for public ip:public port"+publicIp+":"+publicPort+ "private ip:private port:"+privateIp+":"+privatePort);
//            throw new InvalidParameterValueException("Unable to find the rule to be updated for public ip:public port"+publicIp+":"+publicPort+ " private ip:private port:"+privateIp+":"+privatePort);
//        }
//    }
//
//    @Override
//    public PortForwardingRuleVO findForwardingRuleById(Long ruleId) {
//        return _firewallRulesDao.findById(ruleId);
//    }


}
