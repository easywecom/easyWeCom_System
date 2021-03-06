package com.easywecom.wecom.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.easywecom.common.annotation.SysProperty;
import com.easywecom.common.constant.Constants;
import com.easywecom.common.constant.GenConstants;
import com.easywecom.common.core.domain.model.LoginUser;
import com.easywecom.common.core.domain.wecom.BaseExtendPropertyRel;
import com.easywecom.common.core.domain.wecom.WeUser;
import com.easywecom.common.enums.CustomerExtendPropertyEnum;
import com.easywecom.common.enums.CustomerTrajectoryEnums;
import com.easywecom.common.enums.ExternalGroupMemberTypeEnum;
import com.easywecom.common.enums.MessageType;
import com.easywecom.common.utils.StringUtils;
import com.easywecom.wecom.client.WeMessagePushClient;
import com.easywecom.wecom.domain.*;
import com.easywecom.wecom.domain.dto.WeMessagePushDTO;
import com.easywecom.wecom.domain.dto.customer.EditCustomerDTO;
import com.easywecom.wecom.domain.dto.message.TextMessageDTO;
import com.easywecom.wecom.domain.entity.customer.WeCustomerExtendProperty;
import com.easywecom.wecom.domain.vo.sop.SopAttachmentVO;
import com.easywecom.wecom.login.util.LoginTokenService;
import com.easywecom.wecom.mapper.WeCustomerMapper;
import com.easywecom.wecom.mapper.WeCustomerTrajectoryMapper;
import com.easywecom.wecom.mapper.WeUserMapper;
import com.easywecom.wecom.service.*;
import joptsimple.internal.Strings;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.beanutils.ConvertUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.sql.Time;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class WeCustomerTrajectoryServiceImpl extends ServiceImpl<WeCustomerTrajectoryMapper, WeCustomerTrajectory> implements WeCustomerTrajectoryService {


    private final WeMessagePushClient weMessagePushClient;
    private final WeCustomerMapper weCustomerMapper;
    private final WeUserMapper weUserMapper;
    private final WeGroupService weGroupService;
    private final WeCustomerExtendPropertyService weCustomerExtendPropertyService;
    private final WeTagService weTagService;
    private final WeOperationsCenterSopDetailService sopDetailService;
    private final WeOperationsCenterSopTaskService sopTaskService;

    @Autowired
    @Lazy
    public WeCustomerTrajectoryServiceImpl(WeMessagePushClient weMessagePushClient, WeCustomerMapper weCustomerMapper, WeUserMapper weUserMapper, WeGroupService weGroupService, WeCustomerExtendPropertyService weCustomerExtendPropertyService, WeTagService weTagService, WeOperationsCenterSopDetailService sopDetailService, WeOperationsCenterSopTaskService sopTaskService) {
        this.weMessagePushClient = weMessagePushClient;
        this.weCustomerMapper = weCustomerMapper;
        this.weUserMapper = weUserMapper;
        this.weGroupService = weGroupService;
        this.weCustomerExtendPropertyService = weCustomerExtendPropertyService;
        this.weTagService = weTagService;
        this.sopDetailService = sopDetailService;
        this.sopTaskService = sopTaskService;
    }

    /**
     * ?????????????????????
     */
    @Override
    public void waitHandleMsg(String url) {
        //?????????????????????????????????
        List<WeCustomerTrajectory> trajectories = this.list(new LambdaQueryWrapper<WeCustomerTrajectory>()
                .ne(WeCustomerTrajectory::getStatus, Constants.DELETE_CODE)
                .last(" AND concat_ws(' ',create_date,start_time)  <= DATE_FORMAT(NOW(),'%Y-%m-%d %H:%i:%s')" +
                        " AND concat_ws(' ',create_date,end_time) >= DATE_FORMAT(NOW(),'%Y-%m-%d %H:%i:%s')"));
        if (CollUtil.isNotEmpty(trajectories)) {

            List<WeCustomer> weCustomers = weCustomerMapper.selectBatchIds(
                    trajectories.stream().map(WeCustomerTrajectory::getExternalUserid).collect(Collectors.toList())
            );
            Map<String, WeCustomer> weCustomerMap
                    = weCustomers.stream().collect(Collectors.toMap(WeCustomer::getExternalUserid, a -> a, (k1, k2) -> k1));
            //?????????????????????
            trajectories.stream().forEach(trajectory -> weMessagePushClient.sendMessageToUser(WeMessagePushDTO.builder()
                            .touser(trajectory.getUserId())
                            .msgtype(MessageType.TEXT.getMessageType())
                            .agentid(Integer.parseInt(trajectory.getAgentId()))
                            .text(TextMessageDTO.builder()
                                    .content("????????????????????????" + weCustomerMap.get(trajectory.getExternalUserid()).getName() + "??????????????????????????????????????????\n<a href=" + url + ">???????????????????????????</a>")
                                    .build())
                            .build(),
                    trajectory.getAgentId(), trajectory.getCorpId()
            ));

        }
    }


    @Override
    public void recordEditCustomerOperation(String corpId, String userId, String externalUserId, String updateBy, EditCustomerDTO dto) {
        if (StringUtils.isAnyBlank(userId, corpId, externalUserId) || dto == null) {
            return;
        }
        List<WeCustomerTrajectory> list = new ArrayList<>();
        Time now = new Time(System.currentTimeMillis());
        try {
            Class clazz = dto.getClass();
            Field[] fields = clazz.getDeclaredFields();
            for (Field field : fields) {
                field.setAccessible(Boolean.TRUE);
                if (field.get(dto) == null) {
                    continue;
                }
                if (field.isAnnotationPresent(SysProperty.class)) {
                    // ???????????????????????????????????????
                    SysProperty sysProperty = field.getAnnotation(SysProperty.class);
                    String name = sysProperty.name();
                    String value = String.valueOf(field.get(dto));
                    // ??????????????????
                    String content = buildContent(updateBy, name);
                    // ??????????????????
                    list.add(WeCustomerTrajectory.builder()
                            .corpId(corpId)
                            .userId(userId)
                            .externalUserid(externalUserId)
                            .trajectoryType(CustomerTrajectoryEnums.Type.INFO.getType())
                            .createDate(new Date())
                            .detail(value)
                            .content(content)
                            .subType(CustomerTrajectoryEnums.SubType.EDIT_REMARK.getType())
                            .startTime(now)
                            .detailId(Constants.DEFAULT_ID)
                            .build()
                    );
                }
            }
            if (CollectionUtils.isNotEmpty(list)) {
                this.saveBatch(list);
            }
        } catch (Exception e) {
            // ???????????????????????? ????????????????????????
            log.error("[????????????????????????]????????????????????????????????????????????????,corpId:{},userId{},customer:{},dto:{}, e:{}", corpId, userId, externalUserId, dto, ExceptionUtils.getStackTrace(e));
        }

    }


    @Override
    public void recordEditExtendPropOperation(String corpId, String userId, String externalUserId, String updateBy, List<BaseExtendPropertyRel> extendProperties) {
        if (StringUtils.isAnyBlank(userId, corpId, externalUserId) || CollectionUtils.isEmpty(extendProperties)) {
            return;
        }
        List<WeCustomerTrajectory> list = new ArrayList<>();
        Time now = new Time(System.currentTimeMillis());
        try {
            // ?????????????????????????????????????????????,??????->??? ?????????
            Map<WeCustomerExtendProperty, String> prop2valueMap = weCustomerExtendPropertyService.mapProperty2Value(extendProperties, corpId);
            // ??????????????????id????????????,??????
            for (Map.Entry<WeCustomerExtendProperty, String> prop : prop2valueMap.entrySet()) {
                if (prop.getKey() == null) {
                    continue;
                }
                // ??????????????????
                list.add(WeCustomerTrajectory.builder()
                        .corpId(corpId)
                        .userId(userId)
                        .externalUserid(externalUserId)
                        .createDate(new Date())
                        .trajectoryType(CustomerTrajectoryEnums.Type.INFO.getType())
                        .content(buildContent(updateBy, prop.getKey().getName()))
                        .subType(CustomerExtendPropertyEnum.getByType(prop.getKey().getType()).getOprSubType().getType())
                        .detail(prop.getValue())
                        .startTime(now)
                        .detailId(Constants.DEFAULT_ID)
                        .build()
                );
            }
            if (CollectionUtils.isNotEmpty(list)) {
                this.saveBatch(list);
            }
        } catch (Exception e) {
            // ???????????????????????? ????????????????????????
            log.error("[????????????????????????]??????????????????????????????,corpId:{},userId{},customer:{},list:{}, e:{}", corpId, userId, externalUserId, extendProperties, ExceptionUtils.getStackTrace(e));
        }
    }

    @Override
    public void recordEditTagOperation(String corpId, String userId, String externalUserId, String updateBy, List<WeTag> editTags) {
        if (StringUtils.isAnyBlank(userId, corpId, externalUserId) || editTags == null) {
            return;
        }
        List<WeCustomerTrajectory> list = new ArrayList<>();
        Time now = new Time(System.currentTimeMillis());
        try {
            String editTagStr = StringUtils.EMPTY;
            if (CollectionUtils.isNotEmpty(editTags)) {
                List<String> tagIdList = editTags.stream().map(WeTag::getTagId).collect(Collectors.toList());
                List<WeTag> tagList = weTagService.list(new LambdaQueryWrapper<WeTag>()
                        .eq(WeTag::getCorpId, corpId)
                        .in(WeTag::getTagId, tagIdList)
                );
                editTagStr = tagList.stream().map(WeTag::getName).collect(Collectors.joining(","));
            }
            list.add(WeCustomerTrajectory.builder()
                    .corpId(corpId)
                    .userId(userId)
                    .externalUserid(externalUserId)
                    .createDate(new Date())
                    .trajectoryType(CustomerTrajectoryEnums.Type.INFO.getType())
                    .content(buildContent(updateBy, GenConstants.CUSTOMER_TAG))
                    .subType(CustomerTrajectoryEnums.SubType.EDIT_TAG.getType())
                    .detail(editTagStr)
                    .startTime(now)
                    .detailId(Constants.DEFAULT_ID)
                    .build()
            );
            if (CollectionUtils.isNotEmpty(list)) {
                this.saveBatch(list);
            }
        } catch (Exception e) {
            // ???????????????????????? ????????????????????????
            log.error("[????????????????????????]??????????????????????????????,corpId:{},userId{},customer:{},list:{}, e:{}", corpId, userId, externalUserId, editTags, ExceptionUtils.getStackTrace(e));
        }
    }

    @Override
    public void recordEditOperation(EditCustomerDTO dto) {
        if (dto == null || StringUtils.isAnyBlank(dto.getCorpId(), dto.getUserId(), dto.getExternalUserid())) {
            return;
        }
        String userId = dto.getUserId();
        String externalUserId = dto.getExternalUserid();
        String corpId = dto.getCorpId();
        String updateBy = dto.getUpdateBy();
        // ??????????????????????????????
        this.recordEditCustomerOperation(corpId, userId, externalUserId, updateBy, dto);
        // ????????????????????????????????????
        this.recordEditExtendPropOperation(corpId, userId, externalUserId, updateBy, dto.getExtendProperties());
        // ??????????????????????????????
        this.recordEditTagOperation(corpId, userId, externalUserId, updateBy, dto.getEditTag());
    }

    /**
     * ??????????????????
     *
     * @param updateBy ?????????
     * @param name     ??????
     * @return ????????????
     */
    private String buildContent(String updateBy, String name) {
        return GenConstants.EDIT_CUSTOMER_RECORD_MSG
                .replace(GenConstants.USER_NAME, updateBy)
                .replace(GenConstants.PROPERTY_NAME, name);
    }


    @Override
    public void saveActivityRecord(List<WeGroupMember> list, String subType) {
        if (CollectionUtils.isEmpty(list) || !CustomerTrajectoryEnums.SubType.isGroupOperation(subType)) {
            log.info("[????????????]??????????????????/????????????,????????????,list:{},type:{}", list, subType);
            return;
        }
        // ????????????????????????????????????
        String model = CustomerTrajectoryEnums.SubType.getByType(subType).getDesc();
        if (StringUtils.isBlank(model)) {
            return;
        }
        Time now = new Time(System.currentTimeMillis());
        // ??????????????????
        String content;
        for (WeGroupMember member : list) {
            if (ExternalGroupMemberTypeEnum.INTERNAL.getType().equals(member.getJoinType())) {
                // ?????????????????????
                continue;
            }
            WeGroup group = weGroupService.getById(member.getChatId());
            if (group == null) {
                log.info("[????????????]??????????????????/????????????,????????????????????????,member:{}", member);
            }
            // ????????????
            content = model.replace(GenConstants.CUSTOMER, member.getMemberName())
                    .replace(GenConstants.GROUP_NAME, group.getGroupName());
            WeCustomerTrajectory trajectory = WeCustomerTrajectory.builder()
                    .externalUserid(member.getUserId())
                    .trajectoryType(CustomerTrajectoryEnums.Type.ACTIVITY.getType())
                    .content(content)
                    .createDate(new Date())
                    .detail(group.getGroupName())
                    .subType(subType)
                    .corpId(member.getCorpId())
                    .startTime(now)
                    .sopTaskIds(Strings.EMPTY)
                    .detailId(Constants.DEFAULT_ID)
                    .build();
            this.saveOrUpdate(trajectory);
        }
    }

    @Override
    public void saveActivityRecord(String corpId, String userId, String externalUserId, String subType) {
        if (StringUtils.isAnyBlank(corpId, userId, externalUserId)
                || !CustomerTrajectoryEnums.SubType.isUserOperation(subType)) {
            log.info("[????????????]??????????????????/???????????????,????????????,corpId:{},user:{},customer:{},type:{}", corpId, userId, externalUserId, subType);
            return;
        }
        // ??????????????????
        String model = CustomerTrajectoryEnums.SubType.getByType(subType).getDesc();
        if (StringUtils.isBlank(model)) {
            return;
        }
        // ??????????????????
        WeCustomer customer = weCustomerMapper.selectWeCustomerById(externalUserId, corpId);
        if (customer == null || customer.getName() == null) {
            log.info("[????????????]??????????????????/???????????????,????????????????????????,corpId:{},user:{},customer:{},type:{}", corpId, userId, externalUserId, subType);
            return;
        }
        // ?????????????????????
        WeUser user = weUserMapper.selectWeUserById(corpId, userId);
        if (user == null || user.getName() == null) {
            log.info("[????????????]??????????????????/???????????????,???????????????????????????,corpId:{},user:{},customer:{},type:{}", corpId, userId, externalUserId, subType);
            return;
        }
        Time now = new Time(System.currentTimeMillis());
        String content = model.replace(GenConstants.CUSTOMER, customer.getName())
                .replace(GenConstants.USER_NAME, user.getName());
        WeCustomerTrajectory trajectory = WeCustomerTrajectory.builder()
                .userId(userId)
                .externalUserid(externalUserId)
                .trajectoryType(CustomerTrajectoryEnums.Type.ACTIVITY.getType())
                .content(content)
                .createDate(new Date())
                .detail(user.getAvatarMediaid())
                .subType(subType)
                .corpId(corpId)
                .startTime(now)
                .build();
        this.saveOrUpdate(trajectory);
    }

    @Override
    public List<WeCustomerTrajectory> listOfTrajectory(String corpId, String externalUserid, Integer trajectoryType, String userId) {
        boolean isTodo = false;
        LambdaQueryWrapper<WeCustomerTrajectory> wrapper = new LambdaQueryWrapper<WeCustomerTrajectory>()
                .eq(WeCustomerTrajectory::getCorpId, corpId)
                .ne(WeCustomerTrajectory::getStatus, CustomerTrajectoryEnums.TodoTaskStatusEnum.DEL.getCode())
                .eq(WeCustomerTrajectory::getExternalUserid, externalUserid)
                .eq(WeCustomerTrajectory::getTrajectoryType, trajectoryType);
        if (CustomerTrajectoryEnums.Type.TO_DO.getType().equals(trajectoryType)) {
            wrapper.eq(WeCustomerTrajectory::getUserId, userId);
            isTodo = true;
        }
        List<WeCustomerTrajectory> trajectoryList = baseMapper.selectList(wrapper);
        if (isTodo) {
            for (WeCustomerTrajectory weCustomerTrajectory : trajectoryList) {
                if (weCustomerTrajectory.getDetailId() != null && StringUtils.isNotBlank(weCustomerTrajectory.getSopTaskIds())) {
                    WeOperationsCenterSopDetailEntity sopDetailEntity = sopDetailService.getById(weCustomerTrajectory.getDetailId());
                    weCustomerTrajectory.setCreateDate(sopDetailEntity.getAlertTime());
                    Long[] taskIdArray = (Long[]) ConvertUtils.convert(weCustomerTrajectory.getSopTaskIds().split(StrUtil.COMMA), Long.class);
                    List<Long> taskIds = new ArrayList<>(Arrays.asList(taskIdArray));
                    //??????
                    List<WeOperationsCenterSopTaskEntity> taskEntityList = sopTaskService.listByIds(taskIds);
                    setSopAttachment(taskEntityList, weCustomerTrajectory);
                }
            }
        }
        // ?????????????????????,??? ??????????????????????????????????????????
        if (CustomerTrajectoryEnums.Type.INFO.getType().equals(trajectoryType)) {
            LoginUser loginUser = LoginTokenService.getLoginUser();
            return trajectoryList.stream().filter(a -> loginUser.getUserId().equals(a.getUserId())).collect(Collectors.toList());
        }
        return trajectoryList;
    }

    private void setSopAttachment(List<WeOperationsCenterSopTaskEntity> taskEntityList, WeCustomerTrajectory weCustomerTrajectory) {
        List<SopAttachmentVO> materialList = new ArrayList<>();
        for (WeOperationsCenterSopTaskEntity taskEntity : taskEntityList) {
            SopAttachmentVO sopAttachmentVO = new SopAttachmentVO();
            BeanUtils.copyProperties(taskEntity, sopAttachmentVO);
            materialList.add(sopAttachmentVO);
        }
        weCustomerTrajectory.setMaterialList(materialList);
    }

}
