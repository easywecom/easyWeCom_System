package com.easywecom.wecom.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.easywecom.common.annotation.DataScope;
import com.easywecom.common.config.RuoYiConfig;
import com.easywecom.common.constant.*;
import com.easywecom.common.core.domain.entity.SysRole;
import com.easywecom.common.core.domain.entity.WeCorpAccount;
import com.easywecom.common.core.domain.model.LoginUser;
import com.easywecom.common.core.domain.wecom.WeDepartment;
import com.easywecom.common.core.domain.wecom.WeUser;
import com.easywecom.common.core.redis.RedisCache;
import com.easywecom.common.enums.*;
import com.easywecom.common.exception.CustomException;
import com.easywecom.common.utils.file.FileUploadUtils;
import com.easywecom.common.utils.spring.SpringUtils;
import com.easywecom.wecom.client.WeAgentClient;
import com.easywecom.wecom.client.WeUserClient;
import com.easywecom.wecom.domain.*;
import com.easywecom.wecom.domain.dto.*;
import com.easywecom.wecom.domain.dto.group.GroupChatListReq;
import com.easywecom.wecom.domain.dto.group.GroupChatListResp;
import com.easywecom.wecom.domain.dto.transfer.GetUnassignedListReq;
import com.easywecom.wecom.domain.dto.transfer.GetUnassignedListResp;
import com.easywecom.wecom.domain.dto.transfer.TransferResignedUserListDTO;
import com.easywecom.wecom.domain.resp.GetAgentResp;
import com.easywecom.wecom.domain.vo.*;
import com.easywecom.wecom.domain.vo.transfer.TransferResignedUserVO;
import com.easywecom.wecom.login.util.LoginTokenService;
import com.easywecom.wecom.mapper.WeUserMapper;
import com.easywecom.wecom.mapper.WeUserRoleMapper;
import com.easywecom.wecom.service.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * ?????????????????????Service???????????????
 *
 * @author admin
 * @date 2020-08-31
 */
@Service
@Slf4j
public class WeUserServiceImpl extends ServiceImpl<WeUserMapper, WeUser> implements WeUserService {
    private final WeUserMapper weUserMapper;
    private final RedisCache redisCache;
    private final WeUserClient weUserClient;
    private final WeDepartmentService weDepartmentService;
    private final WeUserRoleMapper weUserRoleMapper;
    private final WeFlowerCustomerRelService weFlowerCustomerRelService;
    private final We3rdAppService we3rdAppService;
    private final WeUserRoleService weUserRoleService;
    private final PageHomeService pageHomeService;
    private final WeGroupService weGroupService;
    private final WeMaterialService weMaterialService;
    private final RuoYiConfig ruoYiConfig;
    private final WeExternalUserMappingUserService weExternalUserMappingUserService;
    private final WeCorpAccountService weCorpAccountService;
    private final WeAgentClient weAgentClient;
    @Autowired
    private WeAuthCorpInfoService weAuthCorpInfoService;


    @Lazy
    @Autowired
    public WeUserServiceImpl(WeDepartmentService weDepartmentService, WeUserMapper weUserMapper, RedisCache redisCache, We3rdAppService we3rdAppService, WeUserClient weUserClient, WeCustomerService weCustomerService, WeUserRoleMapper weUserRoleMapper, WeFlowerCustomerRelService weFlowerCustomerRelService, WeUserRoleService weUserRoleService, PageHomeService pageHomeService, WeGroupService weGroupService, WeMaterialService weMaterialService, WeExternalUserMappingUserService weExternalUserMappingUserService, RuoYiConfig ruoYiConfig, WeCorpAccountService weCorpAccountService, WeAgentClient weAgentClient) {
        this.weDepartmentService = weDepartmentService;
        this.weUserMapper = weUserMapper;
        this.redisCache = redisCache;
        this.we3rdAppService = we3rdAppService;
        this.weUserClient = weUserClient;
        this.weUserRoleMapper = weUserRoleMapper;
        this.weFlowerCustomerRelService = weFlowerCustomerRelService;
        this.weUserRoleService = weUserRoleService;
        this.pageHomeService = pageHomeService;
        this.weGroupService = weGroupService;
        this.weMaterialService = weMaterialService;
        this.weExternalUserMappingUserService = weExternalUserMappingUserService;
        this.ruoYiConfig = ruoYiConfig;
        this.weCorpAccountService = weCorpAccountService;
        this.weAgentClient = weAgentClient;
    }

    /**
     * ?????????????????????????????????????????????
     *
     * @param weUserRole ????????????
     */
    @Override
    public void updateUserRole(WeUserRole weUserRole) {
        LambdaUpdateWrapper<WeUserRole> updateWrapper = new LambdaUpdateWrapper<>();
        int i = weUserRoleMapper.update(weUserRole, updateWrapper.eq(WeUserRole::getCorpId, weUserRole.getCorpId()).eq(WeUserRole::getUserId, weUserRole.getUserId()));
        if (i <= 0) {
            weUserRoleMapper.insertUserRole(weUserRole);
        }
    }

    /**
     * ??????????????????
     *
     * @param queryUserDTO ????????????
     * @return vo
     */
    @Override
    @DataScope
    public List<WeUserVO> listOfUser(QueryUserDTO queryUserDTO) {
        if (StringUtils.isEmpty(queryUserDTO.getDepartments())) {
            return Collections.emptyList();
        }
        return weUserMapper.listOfUser(queryUserDTO);
    }

    /**
     * ??????????????????
     *
     * @param corpId          ??????ID
     * @param queryUserIdList ????????????????????????
     * @return {@link List < WeUserVO >}
     */
    @Override
    public List<WeUserVO> listOfUser(String corpId, List<String> queryUserIdList) {
        if (StringUtils.isBlank(corpId)) {
            return new ArrayList<>();
        }
        List<WeUserVO> list = weUserMapper.listOfUser1(corpId, queryUserIdList);
        if (list == null) {
            return new ArrayList<>();
        }
        return list;
    }

    /**
     * ???????????????????????????????????????
     *
     * @param corpId
     * @param userId ??????id
     * @return vo
     */
    @Override
    public WeUserVO getUser(String corpId, String userId) {
        return weUserMapper.getUser(corpId, userId);
    }

    @Override
    public List<String> listOfUserId(String corpId, String[] departments) {
        if (StringUtils.isBlank(corpId)) {
            throw new CustomException(ResultTip.TIP_MISS_CORP_ID);
        }
        if(departments.length > 0){
            return weUserMapper.listOfUserId(corpId, departments);
        }
        return Collections.emptyList();
    }

    /**
     * ?????????????????????????????????????????????
     *
     * @param corpId
     * @param departments
     * @return
     */
    @Override
    public List<String> listOfUserId(String corpId, String departments) {
        if (StringUtils.isBlank(corpId)) {
            throw new CustomException(ResultTip.TIP_MISS_CORP_ID);
        }
        if (StringUtils.isBlank(departments)) {
            return Collections.emptyList();
        }
        String[] departmentsArr = departments.split(StrUtil.COMMA);
        return weUserMapper.listOfUserId(corpId, departmentsArr);
    }

    /**
     * ?????????????????????????????????????????????
     *
     * @param corpId
     * @param departments
     * @return
     */
    @Override
    public List<String> listOfUserId(String corpId, List<Long> departments) {
        if (StringUtils.isBlank(corpId)) {
            throw new CustomException(ResultTip.TIP_MISS_CORP_ID);
        }
        if (CollectionUtils.isEmpty(departments)) {
            return Collections.emptyList();
        }
        String[] departmentsArr = departments.stream().map(x -> x + "").toArray(String[]::new);
        return weUserMapper.listOfUserId(corpId, departmentsArr);
    }

    /**
     * ???????????????????????????
     *
     * @param corpId
     * @param userId ?????????????????????ID
     * @return ?????????????????????
     */
    @Override
    public WeUser selectWeUserById(String corpId, String userId) {
        return weUserMapper.selectWeUserById(corpId, userId);
    }

    @Override
    public WeUser getUserDetail(String corpId, String userId) {
        return weUserMapper.getUserDetail(corpId, userId);
    }

    /**
     * ????????????????????? ??????????????????)
     *
     * @param weUser ?????????????????????
     * @return ?????????????????????
     */
    @Override
    @DataScope
    public List<WeUser> selectWeUserList(WeUser weUser) {
        return this.selectBaseList(weUser);

    }

    /**
     * ????????????????????????(?????????id)?????? (?????????????????????)
     *
     * @param weUser
     * @return
     */
    @Override
    public List<WeUserBriefInfoVO> selectWeUserBriefInfo(WeUser weUser) {
        List<WeUser> list = this.selectBaseList(weUser);
        if (CollUtil.isEmpty(list)) {
            return Collections.emptyList();
        }
        return list.stream().map(WeUserBriefInfoVO::new).collect(Collectors.toList());
    }

    /**
     * ??????????????????
     *
     * @param weUser
     * @return
     */
    private List<WeUser> selectBaseList(WeUser weUser) {
        String[] department = weUser.getDepartment();
        if (ArrayUtil.isNotEmpty(department)) {
            weUser.setDepartmentStr(StringUtils.join(department, ","));
        }
        return weUserMapper.selectWeUserList(weUser);

    }

    /**
     * ???????????????????????????
     *
     * @param weUser ?????????????????????
     * @return ??????
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void insertWeUser(WeUser weUser) {
        if (weUser == null || StringUtils.isAnyBlank(weUser.getCorpId(), weUser.getUserId()) || weUser.getRoleId() == null || weUser.getMainDepartment() == null) {
            throw new CustomException(ResultTip.TIP_PARAM_MISSING);
        }
        // 1. ????????????????????????
        SysRole role = weUserRoleMapper.selectByRoleId(weUser.getCorpId(), weUser.getRoleId());
        if (role == null) {
            throw new CustomException(ResultTip.TIP_ROLE_NOT_EXIST);
        }
        // ??????????????????
        String isLeader = WeConstans.corpUserEnum.IS_DEPARTMENT_SUPERIOR_NO.getKey().toString();
        if (role.getRoleType() != null && RoleTypeEnum.SYS_ADMIN.getType().equals(role.getRoleType())) {
            isLeader = WeConstans.corpUserEnum.IS_DEPARTMENT_SUPERIOR_YES.getKey().toString();
        }
        String[] isLeaderArr = new String[]{isLeader};
        weUser.setIsLeaderInDept(isLeaderArr);
        // 2. ????????????????????????
        WeDepartment department = weDepartmentService.getOne(new LambdaQueryWrapper<WeDepartment>().eq(WeDepartment::getCorpId, weUser.getCorpId()).eq(WeDepartment::getId, weUser.getMainDepartment()));
        if (department == null) {
            throw new CustomException(ResultTip.TIP_DEPARTMENT_NOT_EXIST);
        }
        String[] deptArr = new String[]{String.valueOf(weUser.getMainDepartment())};
        weUser.setDepartment(deptArr);
        // 3.??????????????????????????????
        WeUserDTO createUserReq = new WeUserDTO(weUser);
        if (StringUtils.isNotBlank(weUser.getAvatarMediaid())) {
            String fileName = weUser.getName() + "headImg";
            WeMediaDTO resp = weMaterialService.uploadTemporaryMaterial(weUser.getAvatarMediaid(), GroupMessageType.IMAGE.getMessageType(), fileName, weUser.getCorpId());
            if (resp == null || StringUtils.isBlank(resp.getMedia_id())) {
                throw new CustomException(ResultTip.TIP_ERROR_UPLOAD_HEAD_IMG);
            }
            // ?????????????????????media id
            createUserReq.setAvatar_mediaid(resp.getMedia_id());
            // ?????????????????????url
            weUser.setAvatarMediaid(resp.getUrl());
        }
        // 4. ????????????-????????????
        WeUserRole weUserRole = WeUserRole.builder().corpId(weUser.getCorpId()).userId(weUser.getUserId()).roleId(weUser.getRoleId()).build();
        // 5.???????????????????????????,????????????API????????????
        if (this.insertWeUserNoToWeCom(weUser) > 0 && weUserRoleMapper.insertUserRole(weUserRole) > 0) {
            weUserClient.createUser(createUserReq, weUser.getCorpId());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insertWeUserNoToWeCom(WeUser weUser) {
        WeUser weUserInfo = weUserMapper.selectWeUserById(weUser.getCorpId(), weUser.getUserId());
        initRoleByDepartmentAndLeader(weUser);
        if (weUserInfo != null) {
            Date dimissionTime = weUserInfo.getDimissionTime();
            //??????????????? ???????????????????????????
            boolean hasLeft = StaffActivateEnum.DELETE.getCode().equals(weUserInfo.getIsActivate()) || StaffActivateEnum.RETIRE.getCode().equals(weUserInfo.getIsActivate());
            if (hasLeft && dimissionTime != null) {
                weUser.setDimissionTime(null);
            }
            return weUserMapper.updateWeUser(weUser);
        }
        return weUserMapper.insertWeUser(weUser);

    }

    /**
     * ????????????
     *
     * @param weUser ??????
     * @return ??????
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateWeUser(WeUser weUser) {
        if (this.updateWeUserNoToWeCom(weUser) > 0) {
            //???????????????????????????(????????????)???????????????????????????????????????
            CheckCorpIdVO checkCorpIdVO = weAuthCorpInfoService.isDkCorp(weUser.getCorpId());
            if (checkCorpIdVO != null && !checkCorpIdVO.isDkCorp()) {
                weUserClient.updateUser(new WeUserDTO(weUser), weUser.getCorpId());
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateWeUserNoToWeCom(WeUser weUser) {
        WeUser weUserInfo = weUserMapper.selectWeUserById(weUser.getCorpId(), weUser.getUserId());
        if (weUserInfo == null) {
            return weUserMapper.insertWeUser(weUser);
        } else {
            return weUserMapper.updateWeUser(weUser);
        }
    }

    /**
     * ????????????????????????????????????????????????
     *
     * @param userId ??????ID
     * @param corpId ??????ID
     * @return ????????????
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateWeUserDataFromWeCom(String userId, String corpId) {
        WeUserDTO weUserDTO = weUserClient.getUserByUserId(userId, corpId);
        WeUser weUser = weUserDTO.transferToWeUser();
        weUser.setCorpId(corpId);
        return this.updateWeUserNoToWeCom(weUser);
    }


    /**
     * ?????????????????????
     *
     * @param weUser
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void startOrStop(WeUser weUser) {
        if (WeConstans.WE_USER_STOP.equals(weUser.getEnable())) {
            weUser.setIsActivate(WeConstans.WE_USER_IS_FORBIDDEN);
        } else {
            weUser.setIsActivate(WeConstans.WE_USER_IS_ACTIVATE);
        }
        this.updateWeUser(weUser);
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    @Async
    public void syncWeUser(String corpId, LoginUser loginUser) {
        syncWeUser(corpId);
        try {
            //????????????????????????
            if (ruoYiConfig.isThirdServer() && loginUser.getWeUser() != null && loginUser.getWeUser().isExternalUser()) {
                WeExternalUserMappingUser mapping = weExternalUserMappingUserService.getMappingByExternal(loginUser.getWeUser().getExternalCorpId(), loginUser.getWeUser().getExternalUserId());
                if (mapping != null && StringUtils.isNoneBlank(mapping.getCorpId(), mapping.getUserId())) {
                    WeUser currentLoginUser = weUserMapper.getUserDetail(mapping.getCorpId(), mapping.getUserId());
                    if (currentLoginUser != null) {
                        currentLoginUser.setExternalUserId(loginUser.getWeUser().getExternalUserId());
                        currentLoginUser.setExternalCorpId(loginUser.getWeUser().getExternalCorpId());
                        loginUser.setWeUser(currentLoginUser);
                        // ??????????????????
                        LoginTokenService.refreshWeUser(loginUser);
                        // ??????????????????
                        LoginTokenService.refreshDataScope(loginUser);
                    }
                }
            }
        } catch (Exception e) {
            log.error("????????????????????????????????????????????????????????????ID???{}????????????{}??????????????????{}", loginUser.getCorpId(), loginUser.getUsername(), ExceptionUtils.getStackTrace(e));
        }
        // ??????????????????????????????????????????
        pageHomeService.getUserData(corpId);
    }

    @Override
    public void syncWeUser(String corpId) {
        if (StringUtils.isBlank(corpId)) {
            log.info("corpId??????,????????????????????????");
            return;
        }
        // ?????????????????????(??????????????????????????????????????????,???????????????????????????????????????,????????????????????????????????????????????????????????????????????????,????????????????????????????????????,?????????????????????)
        try {
            SpringUtils.getBean(WeUserService.class).syncWeLeaveUserV2(corpId);
        } catch (Exception e) {
            log.error("????????????????????????corpId:{},E:{}", corpId, ExceptionUtils.getStackTrace(e));
        }
        log.info("??????????????????,corpId:{}", corpId);
        List<WeUser> visibleUser = this.getVisibleUser(corpId);
        if (CollUtil.isEmpty(visibleUser)) {
            log.info("[????????????]???????????????????????????????????????,corpId:{}", corpId);
            return;
        }
        List<WeUser> exitsUsers = weUserMapper.selectList(new LambdaQueryWrapper<WeUser>().eq(WeUser::getCorpId, corpId).ne(WeUser::getIsActivate, WeConstans.WE_USER_IS_LEAVE));
        //????????????????????????
        Map<String, String> userMap = visibleUser.stream().collect(Collectors.toMap(WeUser::getUserId, WeUser::getUserId));
        //???????????????????????????????????????????????????????????????
        weExternalUserMappingUserService.createMapping(corpId, new ArrayList<>(userMap.keySet()));
        List<String> delIds = new ArrayList<>();
        if (CollUtil.isNotEmpty(exitsUsers)) {
            for (WeUser eUser : exitsUsers) {
                if (!userMap.containsKey(eUser.getUserId())) {
                    delIds.add(eUser.getUserId());
                }
            }
        }
        deleteUsersNoToWeCom(corpId, delIds);
        //?????????????????????
        for (WeUser weUser : visibleUser) {
            weUser.setCorpId(corpId);
            insertWeUserNoToWeCom(weUser);
        }
        log.info("??????????????????,corpId:{},????????????????????????{}", corpId, visibleUser.size());
        // ??????????????????????????????????????????
        pageHomeService.getUserData(corpId);
    }

    @Override
    public List<WeUser> getVisibleUser(String corpId) {
        if (StringUtils.isBlank(corpId)) {
            return Collections.emptyList();
        }
        // ??????agentId
        WeCorpAccount corpAccount = weCorpAccountService.findValidWeCorpAccount(corpId);
        if (corpAccount == null || StringUtils.isBlank(corpAccount.getAgentId())) {
            return Collections.emptyList();
        }
        Set<WeUser> visibleUsers = new HashSet<>();
        // ?????????????????????????????????+ ????????????)
        GetAgentResp resp = weAgentClient.getAgent(corpAccount.getAgentId(), corpId);
        // ??????????????????????????????
        if (CollectionUtils.isNotEmpty(resp.getAllow_partys().getPartyid())) {
            for (Integer party : resp.getAllow_partys().getPartyid()) {
                List<WeUser> tempList = weUserClient.list(Long.valueOf(party), WeConstans.DEPARTMENT_SUB_WEUSER, corpId).getWeUsers();
                if (CollectionUtils.isNotEmpty(tempList)) {
                    visibleUsers.addAll(tempList);
                }
            }
        }
        // ???????????????????????????????????????
        if (CollectionUtils.isNotEmpty(resp.getAllow_userinfos().getUser())) {
            for (GetAgentResp.User user : resp.getAllow_userinfos().getUser()) {
                WeUserDTO dto = weUserClient.getUserByUserId(user.getUserid(), corpId);
                if (dto != null) {
                    visibleUsers.add(dto.transferToWeUser());
                }
            }
        }
        return new ArrayList<>(visibleUsers);
    }


    @Override
    public void syncWeLeaveUserV2(String corpId) {
        if (StringUtils.isBlank(corpId)) {
            log.info("[??????????????????]????????????ID,corpId:{}", corpId);
            return;
        }
        log.info("?????????????????????????????????V2??????,corpId:{}", corpId);
        // 1. ????????????API???????????????????????? (?????????????????????????????????)
        GroupChatListReq groupReq = GroupChatListReq.builder().status_filter(GroupConstants.OWNER_LEAVE).build();
        GroupChatListResp groupResp = (GroupChatListResp) groupReq.executeTillNoNextPage(corpId);
        List<String> chatIdList = groupResp.getChatIdList();
        // 2.????????????????????????:???????????????
        if (CollectionUtils.isNotEmpty(chatIdList)) {
            WeGroup entity = new WeGroup();
            entity.setStatus(GroupConstants.OWNER_LEAVE);
            weGroupService.update(entity, new LambdaUpdateWrapper<WeGroup>().eq(WeGroup::getCorpId, corpId).in(WeGroup::getChatId, chatIdList));
        }
        // 3. ?????????????????????????????????????????????????????????
        GetUnassignedListReq req = new GetUnassignedListReq();
        GetUnassignedListResp resp = (GetUnassignedListResp) req.executeTillNoNextPage(corpId);
        resp.handleData(corpId);
        if (CollectionUtils.isEmpty(resp.getTotalList())) {
            log.info("[??????????????????]??????????????????????????????????????????corpId:{},resp:{}", corpId, resp);
            return;
        }
        // 4.???????????????????????????????????????
        List<WeUser> updateUserList = resp.getUpdateUserList();
        if (CollectionUtils.isNotEmpty(updateUserList)) {
            weUserMapper.batchUpdateWeUser(updateUserList);
        }
        // 5. ???????????????????????????????????????
        List<WeFlowerCustomerRel> relList = resp.getRelList();
        if (CollectionUtils.isNotEmpty(relList)) {
            weFlowerCustomerRelService.batchUpdateStatus(relList);
        }
        log.info("????????????????????????V2??????,corpId:{}", corpId);
    }


    /**
     * ????????????
     *
     * @param corpId
     * @param ids
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteUser(String corpId, String[] ids) {
        if (StringUtils.isBlank(corpId) || ArrayUtil.isEmpty(ids)) {
            return;
        }
        List<WeUser> weUsers = new ArrayList<>();
        CollUtil.newArrayList(ids).forEach(id -> weUsers.add(WeUser.builder().corpId(corpId).userId(id).isActivate(WeConstans.WE_USER_IS_LEAVE).dimissionTime(new Date()).build()));

        if (this.baseMapper.batchUpdateWeUser(weUsers) > 0) {
            weUsers.forEach(weUser -> weUserClient.deleteUserByUserId(weUser.getUserId(), corpId));
        }
        // ???????????????????????? ????????????????????????
        redisCache.setCacheObject(RedisKeyConstants.DELETE_USER_KEY + corpId, corpId, 30, TimeUnit.SECONDS);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteUserNoToWeCom(String userId, String corpId) {
        if (StringUtils.isAnyBlank(userId, corpId)) {
            log.error("??????id?????????id????????????");
            return 0;
        }
        WeUser weUser = WeUser.builder().userId(userId).isActivate(WeConstans.WE_USER_IS_LEAVE).corpId(corpId).dimissionTime(new Date()).build();
        return weUserMapper.update(weUser, new LambdaQueryWrapper<WeUser>().eq(WeUser::getUserId, userId).eq(WeUser::getCorpId, corpId));
    }

    private Map<String, WeFlowerCustomerRel> getWeCustomerMap(String corpId, WeUser weUser) {
        if (StringUtils.isBlank(corpId) || weUser == null) {
            return new HashMap<>(1);
        }
        //????????????????????????????????????
        List<WeFlowerCustomerRel> weFlowerCustomers = weFlowerCustomerRelService.list(new LambdaQueryWrapper<WeFlowerCustomerRel>().eq(WeFlowerCustomerRel::getCorpId, corpId).eq(WeFlowerCustomerRel::getUserId, weUser.getUserId()));

        Map<String, WeFlowerCustomerRel> map = new HashMap<>(weFlowerCustomers.size());
        for (WeFlowerCustomerRel weFlowerCustomerRel : weFlowerCustomers) {
            if (map.containsKey(weFlowerCustomerRel.getExternalUserid())) {
                continue;
            }
            map.put(weFlowerCustomerRel.getExternalUserid(), weFlowerCustomerRel);
        }
        return map;
    }


    @Override
    public void deleteUsersNoToWeCom(String corpId, List<String> ids) {
        List<WeUser> weUsers = new ArrayList<>();
        ids.forEach(id -> weUsers.add(WeUser.builder().corpId(corpId).userId(id).isActivate(WeConstans.WE_USER_IS_LEAVE).dimissionTime(new Date()).build()));
        if (CollUtil.isNotEmpty(weUsers)) {
            weUserMapper.batchUpdateWeUser(weUsers);
        }
    }

    @Override
    public WeUserInfoVO getUserInfo(String code, String agentId, String corpId) {
        String cacheKey = "USER_INFO:" + corpId + ":" + agentId + ":" + code;
        WeUserInfoDTO getuserinfo = redisCache.getCacheObject(cacheKey);
        if (ObjectUtils.isEmpty(getuserinfo)) {
            getuserinfo = weUserClient.getuserinfo(code, agentId, corpId);
            redisCache.setCacheObject(cacheKey, getuserinfo, 5, TimeUnit.MINUTES);
        }
        return WeUserInfoVO.builder().userId(getuserinfo.getUserId()).deviceId(getuserinfo.getDeviceId()).externalUserId(getuserinfo.getExternal_userid()).openId(getuserinfo.getOpenId()).build();

    }

    @Override
    public List<WeCustomerAddUser> findWeUserByCutomerId(String corpId, String externalUserid) {
        return this.baseMapper.findWeUserByCutomerId(corpId, externalUserid);
    }

    @Override
    public void batchInitRoleByDepartmentAndLeader(List<WeUser> userList) {
        if (CollectionUtils.isEmpty(userList)) {
            return;
        }
        // ??????ID
        String corpId = userList.get(0).getCorpId();
        if (StringUtils.isBlank(corpId)) {
            log.info("?????????????????????:corpId??????,???????????????{}", userList.get(0));
            return;
        }
        // ????????????id?????????(key: ??????KEY , VALUE: ??????ID )
        // ??????????????????????????????????????????????????????,????????????????????????????????????3???????????????id
        HashMap<String, Long> roleIdMap = new HashMap<>(16);
        // ???????????????????????????
        boolean isInRootDepartment;
        // ???????????????
        boolean isLeader;
        List<WeUserRole> needToInitRoleList = new ArrayList<>();
        for (WeUser user : userList) {
            // ???????????????????????????????????? , corpId????????????????????????
            if (ObjectUtil.isNotNull(user.getRoleId()) || !corpId.equals(user.getCorpId())) {
                continue;
            }
            Long initRoleId;
            isInRootDepartment = isInRootDepartment(user.getMainDepartment());
            isLeader = isLeaderInMainDepartment(user.getDepartment(), user.getIsLeaderInDept(), user.getMainDepartment());
            // ?????????????????????????????????????????????
            // ???????????????????????????????????? ??????????????????????????????????????????????????????????????????????????? ; ???????????????????????????????????????
            if (isInRootDepartment && isLeader) {
                initRoleId = getInitRoleIdByKey(corpId, roleIdMap, UserConstants.INIT_ADMIN_ROLE_KEY);
            } else if (isLeader) {
                initRoleId = getInitRoleIdByKey(corpId, roleIdMap, UserConstants.INIT_DEPARTMENT_ADMIN_ROLE_KEY);
            } else {
                initRoleId = getInitRoleIdByKey(corpId, roleIdMap, UserConstants.INIT_EMPLOYEE_ROLE_KEY);
            }
            //????????????????????????????????????????????????
            if (ruoYiConfig.isThirdServer()) {
                initRoleId = initRole4ThirdServer(corpId, user, roleIdMap);
            }
            if (initRoleId != null) {
                needToInitRoleList.add(new WeUserRole(user.getCorpId(), user.getUserId(), initRoleId));
            }
        }
        //???????????? ??????-?????????
        if (CollUtil.isNotEmpty(needToInitRoleList)) {
            weUserRoleMapper.batchInsertUserRole(needToInitRoleList);
            log.info("??????????????????????????????,????????????:{},corpId:{}", needToInitRoleList.size(), corpId);
        }
    }

    /**
     * ??????????????????????????????
     *
     * @param corpId    ??????id
     * @param user      ????????????
     * @param roleIdMap ?????? (key: ??????KEY , VALUE: ??????ID )
     * @return ??????????????????id
     */
    public Long initRole4ThirdServer(String corpId, WeUser user, HashMap<String, Long> roleIdMap) {
        if (StringUtils.isBlank(user.getExternalCorpId())) {
            WeCorpAccount weCorpAccount = weCorpAccountService.findValidWeCorpAccount(user.getCorpId());
            user.setExternalCorpId(weCorpAccount.getExternalCorpId());
        }
        Map<String, Integer> adminMap = new HashMap<>();
        if (StringUtils.isNotBlank(user.getExternalCorpId())) {
            adminMap = we3rdAppService.getAdminList(user.getExternalCorpId());
        }
        WeExternalUserMappingUser weExternalUserMappingUser = weExternalUserMappingUserService.getMappingByInternal(user.getCorpId(), user.getUserId());
        if (weExternalUserMappingUser != null && StringUtils.isNoneBlank(weExternalUserMappingUser.getExternalCorpId(), weExternalUserMappingUser.getCorpId(), weExternalUserMappingUser.getUserId(), weExternalUserMappingUser.getExternalUserId())) {
            user.setExternalCorpId(weExternalUserMappingUser.getExternalCorpId());
            user.setCorpId(weExternalUserMappingUser.getCorpId());
            user.setUserId(weExternalUserMappingUser.getUserId());
            user.setExternalUserId(weExternalUserMappingUser.getExternalUserId());
        }
        if (ObjectUtil.isNotEmpty(adminMap) && adminMap.containsKey(user.getExternalUserId())) {
            return getInitRoleIdByKey(corpId, roleIdMap, UserConstants.INIT_ADMIN_ROLE_KEY);
        }
        return getInitRoleIdByKey(corpId, roleIdMap, UserConstants.INIT_EMPLOYEE_ROLE_KEY);
    }

    /**
     * ??????roleKey??????roleId ,
     * ???????????????????????????????????? ,????????????????????????????????????????????????????????????
     *
     * @param corpId    ??????ID
     * @param roleIdMap ??????ID??????Map
     * @param roleKey   ??????Key
     * @return ???????????????ID
     */
    private Long getInitRoleIdByKey(String corpId, HashMap<String, Long> roleIdMap, String roleKey) {
        return roleIdMap.computeIfAbsent(roleKey, key -> weUserRoleService.selectRoleIdByCorpIdAndRoleKey(corpId, roleKey));
    }

    /**
     * ??????????????????????????????
     *
     * @param mainDepartment ??????????????????
     * @return true ???????????? false ???????????????
     */
    private boolean isInRootDepartment(Long mainDepartment) {
        return !ObjectUtil.isNull(mainDepartment) && WeConstans.WE_ROOT_DEPARMENT_ID.equals(mainDepartment);
    }

    /**
     * ????????????????????????????????????
     *
     * @param department     ??????ID?????? ????????????????????????ID, ?????? [7,1,8]
     * @param isLeaderInDept ??????????????????????????????(1???0???) ???????????????????????????,??????[0,0,0]
     * @param mainDepartment ?????????ID
     * @return true ????????????????????????  false ????????????????????????
     */
    private boolean isLeaderInMainDepartment(String[] department, String[] isLeaderInDept, Long mainDepartment) {
        // ????????????
        if (ObjectUtil.isNull(mainDepartment) || ArrayUtil.isEmpty(department) || ArrayUtil.isEmpty(isLeaderInDept)) {
            return false;
        }
        // ???????????????????????????????????? ??????????????????,??????????????????
        if (department.length != isLeaderInDept.length) {
            return false;
        }
        // ??????????????????????????????isLeaderInDept????????????????????????  ?????????????????????
        if (department.length == 1) {
            return WeConstans.corpUserEnum.IS_DEPARTMENT_SUPERIOR_YES.getKey().toString().equals(isLeaderInDept[0]);
        }
        // ????????????????????????????????????,????????????????????????????????? ???????????????isLeader??????????????????1 (???????????????)
        for (int i = 0; i < department.length; i++) {
            if (!mainDepartment.toString().equals(department[i])) {
                continue;
            }
            return WeConstans.corpUserEnum.IS_DEPARTMENT_SUPERIOR_YES.getKey().toString().equals(isLeaderInDept[i]);
        }
        return false;
    }

    @Override
    public void initRoleByDepartmentAndLeader(WeUser weUser) {
        List<WeUser> list = new ArrayList<>();
        list.add(weUser);
        batchInitRoleByDepartmentAndLeader(list);
    }

    @Override
    public List<WeUser> getUserInDataScope(LoginUser loginUser) {
        if (ObjectUtil.isNull(loginUser) || StringUtils.isBlank(loginUser.getCorpId()) || StringUtils.isBlank(loginUser.getDepartmentDataScope())) {
            return Collections.emptyList();
        }
        String corpId = loginUser.getCorpId();
        // ?????????????????????????????????????????????????????????(?????????1,2,3)
        String dataScope = loginUser.getDepartmentDataScope();
        // ????????????ID??????
        String[] array = {"-1"};
        try {
            array = dataScope.split(",");
        } catch (Exception e) {
            log.info("??????????????????????????????????????????????????????:????????????????????????,user:{},e:{}", loginUser, ExceptionUtils.getStackTrace(e));
        }
        // ???????????????????????????????????? ?????????????????????????????????????????????
        return weUserMapper.getUserByDepartmentList(corpId, array);
    }

    @Override
    public String getJoinQrCode(String corpId) {
        if (StringUtils.isBlank(corpId)) {
            throw new CustomException(ResultTip.TIP_MISS_CORP_ID);
        }
        GetJoinQrCodeResp resp = weUserClient.getJoinQrCode(corpId);
        if (resp == null || StringUtils.isBlank(resp.getJoin_qrcode())) {
            throw new CustomException(ResultTip.TIP_FAIL_GET_JOIN_CORP_QRCODE);
        }
        return resp.getJoin_qrcode();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BatchUpdateUserInfoVO batchUpdateUserInfo(BatchUpdateUserInfoDTO updateUserInfo) {
        //??????????????????
        verifyParam(updateUserInfo);
        Integer type = updateUserInfo.getType();
        String corpId = updateUserInfo.getCorpId();
        if (BatchUpdateUserInfoTypeEnum.ROLE.getType().equals(type)) {
            return batchUpdateUserRole(corpId, updateUserInfo.getUserIdList(), updateUserInfo.getRoleId());
        } else if (BatchUpdateUserInfoTypeEnum.POSITION.getType().equals(type)) {
            return batchUpdateUserInfo(corpId, updateUserInfo.getUserIdList(), updateUserInfo.getPosition(), null);
        } else {
            return batchUpdateUserInfo(corpId, updateUserInfo.getUserIdList(), null, updateUserInfo.getDepartment());
        }
    }

    /**
     * ????????????????????????
     *
     * @param corpId     ??????ID
     * @param userIdList ????????????
     * @param position   ??????
     * @return BatchUpdateUserInfoVO
     */
    private BatchUpdateUserInfoVO batchUpdateUserInfo(String corpId, List<UpdateUserInfoDetailDTO> userIdList, String position, Long department) {
        if (StringUtils.isBlank(corpId) || CollectionUtils.isEmpty(userIdList) || StringUtils.isBlank(position) && department == null) {
            throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
        }
        int successCount = 0;
        int failCount = 0;
        WeResultDTO weResultDTO;
        StringBuilder failMsg = new StringBuilder();
        WeUserDTO weUserDTO;
        String[] departArr = department != null ? new String[]{department.toString()} : null;
        List<String> successUserIdList = new ArrayList<>();
        for (UpdateUserInfoDetailDTO detailDTO : userIdList) {
            if (StringUtils.isBlank(detailDTO.getUserId())) {
                continue;
            }
            weUserDTO = new WeUserDTO();
            weUserDTO.setUserid(detailDTO.getUserId());
            if (StringUtils.isNotBlank(position)) {
                weUserDTO.setPosition(position);
            } else {
                weUserDTO.setDepartment(departArr);
            }
            try {
                weUserClient.updateUser(weUserDTO, corpId);
                successUserIdList.add(detailDTO.getUserId());
                successCount++;
            } catch (Exception e) {
                failCount++;
                weResultDTO = JSONObject.parseObject(e.getMessage(), WeResultDTO.class);
                failMsg.append("?????????").append(detailDTO.getUserName()).append(", ???????????????").append(weResultDTO.getErrmsg()).append("\n");
            }
        }
        //????????????????????????/??????
        if (CollectionUtils.isNotEmpty(successUserIdList)) {
            weUserMapper.batchUpdateWeUserPositionOrDepartment(corpId, successUserIdList, position, department);
        }

        //????????????
        String fileUrl = getFileMsgUrl(failMsg.toString());
        return new BatchUpdateUserInfoVO(successCount, failCount, fileUrl);
    }

    /**
     * ??????????????????
     *
     * @param failMsg ????????????
     * @return ????????????
     */
    private String getFileMsgUrl(String failMsg) {
        String fileUrl = null;
        //????????????
        if (StringUtils.isNotBlank(failMsg)) {
            String fileName = System.currentTimeMillis() + "??????????????????????????????." + GenConstants.SUFFIX_TXT;
            try {
                String url = FileUploadUtils.upload2Cos(new ByteArrayInputStream(failMsg.getBytes(StandardCharsets.UTF_8)), fileName, GenConstants.SUFFIX_TXT, ruoYiConfig.getFile().getCos());
                fileUrl = ruoYiConfig.getFile().getCos().getCosImgUrlPrefix() + url;
            } catch (Exception e) {
                log.error("batchUpdateUserInfoPosition error! {}", ExceptionUtils.getStackTrace(e));
            }
        }
        return fileUrl;
    }


    /**
     * ????????????????????????
     *
     * @param corpId     ??????ID
     * @param userIdList ????????????
     * @param roleId     ??????ID
     * @return BatchUpdateUserInfoVO
     */
    private BatchUpdateUserInfoVO batchUpdateUserRole(String corpId, List<UpdateUserInfoDetailDTO> userIdList, Long roleId) {
        if (StringUtils.isBlank(corpId) || CollectionUtils.isEmpty(userIdList) || roleId == null) {
            throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
        }
        List<WeUserRole> list = new ArrayList<>();
        WeUserRole weUserRole;
        for (UpdateUserInfoDetailDTO detailDTO : userIdList) {
            if (StringUtils.isBlank(detailDTO.getUserId())) {
                throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
            }
            weUserRole = new WeUserRole(corpId, detailDTO.getUserId(), roleId);
            list.add(weUserRole);
        }
        weUserRoleMapper.batchInsertOrUpdateUserRole(list);
        return new BatchUpdateUserInfoVO(list.size(), 0, null);
    }

    /**
     * ??????????????????
     *
     * @param batchUpdateUserInfoDTO batchUpdateUserInfoDTO
     */
    private void verifyParam(BatchUpdateUserInfoDTO batchUpdateUserInfoDTO) {
        if (batchUpdateUserInfoDTO == null || StringUtils.isBlank(batchUpdateUserInfoDTO.getCorpId()) || batchUpdateUserInfoDTO.getType() == null || CollectionUtils.isEmpty(batchUpdateUserInfoDTO.getUserIdList())) {
            throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
        }
        //??????????????????????????????????????????
        Integer type = batchUpdateUserInfoDTO.getType();
        if (BatchUpdateUserInfoTypeEnum.ROLE.getType().equals(type)) {
            if (batchUpdateUserInfoDTO.getRoleId() == null) {
                throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
            }
        } else if (BatchUpdateUserInfoTypeEnum.POSITION.getType().equals(type)) {
            if (StringUtils.isBlank(batchUpdateUserInfoDTO.getPosition())) {
                throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
            }
        } else if (BatchUpdateUserInfoTypeEnum.DEPARTMENT.getType().equals(type)) {
            if (batchUpdateUserInfoDTO.getDepartment() == null) {
                throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
            }
        } else {
            throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
        }
    }

    @Override
    public void validateActiveUser(String corpId, String userId) {
        WeUser user = this.getOne(new LambdaQueryWrapper<WeUser>().eq(WeUser::getCorpId, corpId).eq(WeUser::getUserId, userId).last(GenConstants.LIMIT_1));
        if (user == null || !StaffActivateEnum.ACTIVE.getCode().equals(user.getIsActivate())) {
            throw new CustomException(ResultTip.TIP_USER_NOT_ACTIVE);
        }
    }

    @Override
    @DataScope
    public List<TransferResignedUserVO> leaveUserListV3(TransferResignedUserListDTO dto) {
        if (dto == null || StringUtils.isBlank(dto.getCorpId()) || dto.getIsAllocate() == null) {
            throw new CustomException(ResultTip.TIP_GENERAL_PARAM_ERROR);
        }
        List<TransferResignedUserVO> list = weUserMapper.leaveUserListV3(dto);
        if (CollectionUtils.isEmpty(list)) {
            return Collections.emptyList();
        }
        return list.stream().filter(a -> a.getAllocateGroupNum() > 0 || a.getAllocateCustomerNum() > 0).collect(Collectors.toList());

    }

    /**
     * ????????????????????????????????????
     *
     * @param externalUserId ????????????id
     * @param externalCorpId ????????????id
     * @return {@link WeUser}
     */
    @Override
    public WeUser getWeUserByExternalMapping(String externalUserId, String externalCorpId) {
        WeExternalUserMappingUser weExternalUserMappingUser = weExternalUserMappingUserService.getMappingByExternal(externalCorpId, externalUserId);
        if (weExternalUserMappingUser != null && StringUtils.isNoneBlank(weExternalUserMappingUser.getUserId(), weExternalUserMappingUser.getCorpId())) {
            WeUserDTO weUserDTO = weUserClient.getUserByUserId(weExternalUserMappingUser.getUserId(), weExternalUserMappingUser.getCorpId());
            if (weUserDTO == null) {
                return null;
            }
            WeUser weUser = weUserDTO.transferToWeUser();
            weUser.setExternalCorpId(externalCorpId);
            weUser.setExternalUserId(externalUserId);
            weUser.setCorpId(weExternalUserMappingUser.getCorpId());
            this.insertWeUserNoToWeCom(weUser);
            weUser.setDepartmentName(weDepartmentService.selectNameByUserId(weExternalUserMappingUser.getCorpId(), weExternalUserMappingUser.getUserId()));
            return weUser;
        } else {
            return null;
        }
    }

}
