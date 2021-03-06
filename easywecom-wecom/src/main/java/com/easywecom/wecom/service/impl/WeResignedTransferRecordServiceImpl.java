package com.easywecom.wecom.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.easywecom.common.annotation.DataScope;
import com.easywecom.common.constant.GenConstants;
import com.easywecom.common.constant.GroupConstants;
import com.easywecom.common.core.domain.wecom.WeUser;
import com.easywecom.common.enums.CustomerStatusEnum;
import com.easywecom.common.enums.CustomerTransferStatusEnum;
import com.easywecom.common.enums.ResultTip;
import com.easywecom.common.enums.StaffActivateEnum;
import com.easywecom.common.exception.CustomException;
import com.easywecom.common.utils.SnowFlakeUtil;
import com.easywecom.common.utils.sql.BatchInsertUtil;
import com.easywecom.wecom.domain.WeFlowerCustomerRel;
import com.easywecom.wecom.domain.WeGroup;
import com.easywecom.wecom.domain.dto.group.GroupChatListReq;
import com.easywecom.wecom.domain.dto.group.GroupChatListResp;
import com.easywecom.wecom.domain.dto.transfer.*;
import com.easywecom.wecom.domain.entity.transfer.WeResignedCustomerTransferRecord;
import com.easywecom.wecom.domain.entity.transfer.WeResignedGroupTransferRecord;
import com.easywecom.wecom.domain.entity.transfer.WeResignedTransferRecord;
import com.easywecom.wecom.domain.vo.transfer.GetResignedTransferCustomerDetailVO;
import com.easywecom.wecom.domain.vo.transfer.GetResignedTransferGroupDetailVO;
import com.easywecom.wecom.domain.vo.transfer.TransferResignedUserVO;
import com.easywecom.wecom.mapper.WeResignedCustomerTransferRecordMapper;
import com.easywecom.wecom.mapper.WeResignedGroupTransferRecordMapper;
import com.easywecom.wecom.mapper.WeResignedTransferRecordMapper;
import com.easywecom.wecom.service.WeFlowerCustomerRelService;
import com.easywecom.wecom.service.WeGroupService;
import com.easywecom.wecom.service.WeResignedTransferRecordService;
import com.easywecom.wecom.service.WeUserService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ??????: ?????????????????????????????????????????????
 *
 * @author : silver_chariot
 * @date : 2021/12/6 14:34
 */
@Service
@Slf4j
public class WeResignedTransferRecordServiceImpl extends ServiceImpl<WeResignedTransferRecordMapper, WeResignedTransferRecord> implements WeResignedTransferRecordService {

    private final WeResignedTransferRecordMapper weResignedTransferRecordMapper;
    private final WeUserService weUserService;
    private final WeResignedGroupTransferRecordMapper weResignedGroupTransferRecordMapper;
    private final WeFlowerCustomerRelService weFlowerCustomerRelService;
    private final WeResignedCustomerTransferRecordMapper weResignedCustomerTransferRecordMapper;
    private final WeGroupService weGroupService;

    public WeResignedTransferRecordServiceImpl(@NotNull WeResignedTransferRecordMapper weResignedTransferRecordMapper, @NotNull WeUserService weUserService,
                                               @NotNull WeResignedGroupTransferRecordMapper weResignedGroupTransferRecordMapper, @NotNull WeFlowerCustomerRelService weFlowerCustomerRelService, WeResignedCustomerTransferRecordMapper weResignedCustomerTransferRecordMapper, WeGroupService weGroupService) {
        this.weResignedTransferRecordMapper = weResignedTransferRecordMapper;
        this.weUserService = weUserService;
        this.weResignedGroupTransferRecordMapper = weResignedGroupTransferRecordMapper;
        this.weFlowerCustomerRelService = weFlowerCustomerRelService;
        this.weResignedCustomerTransferRecordMapper = weResignedCustomerTransferRecordMapper;
        this.weGroupService = weGroupService;
    }

    @Override
    public void transfer(String corpId, List<TransferResignedUserDTO.LeaveUserDetail> handoverUserList, String takeoverUserId, String chatId, String externalUserid) {
        if (StringUtils.isAnyBlank(corpId, takeoverUserId)
                || CollectionUtils.isEmpty(handoverUserList)) {
            throw new CustomException(ResultTip.TIP_GENERAL_PARAM_ERROR);
        }
        // ???????????????????????????????????????(?????????API??????????????????????????????)
        List<GetUnassignedListResp.UnassignedInfo> unassignedList = this.getUnassignedList(corpId);
        // ??????????????? ?????????????????? (??????API??????????????????????????????????????????)
        for (TransferResignedUserDTO.LeaveUserDetail leaveUser : handoverUserList) {
            this.transfer(corpId, leaveUser, takeoverUserId, unassignedList, chatId, externalUserid);
        }
    }

    @Override
    public void transfer(String corpId, TransferResignedUserDTO.LeaveUserDetail handoverUser, String takeoverUserId, List<GetUnassignedListResp.UnassignedInfo> unassignedList, String chatId, String externalUserid) {
        if (handoverUser == null || StringUtils.isAnyBlank(corpId, takeoverUserId, handoverUser.getUserId())) {
            throw new CustomException(ResultTip.TIP_GENERAL_PARAM_ERROR);
        }
        String handoverUserId = handoverUser.getUserId();
        // 1. ????????????????????????
        WeUser takeoverUser = weUserService.getUserDetail(corpId, takeoverUserId);
        if (takeoverUser == null || StringUtils.isBlank(takeoverUser.getDepartmentName())
                || !StaffActivateEnum.ACTIVE.getCode().equals(takeoverUser.getIsActivate())) {
            throw new CustomException(ResultTip.TIP_TAKEOVER_USER_ERROR);
        }
        // 2. ???????????????????????????
        WeUser handoverUserDetail = weUserService.getUserDetail(corpId, handoverUserId);
        if (handoverUserDetail == null || StringUtils.isAnyBlank(handoverUserDetail.getUserId(), handoverUserDetail.getDepartmentName())) {
            throw new CustomException(ResultTip.TIP_HANDOVER_USER_ERROR);
        }
        //  3.???????????????????????????,??????????????????
        WeResignedTransferRecord record = weResignedTransferRecordMapper.get(corpId, handoverUserId, takeoverUserId, handoverUser.getDimissionTime());
        if (record == null) {
            record = WeResignedTransferRecord.builder()
                    .id(SnowFlakeUtil.nextId())
                    .corpId(corpId)
                    .handoverUserid(handoverUser.getUserId())
                    .takeoverUserid(takeoverUserId)
                    .dimissionTime(handoverUser.getDimissionTime())
                    .takeoverUsername(takeoverUser.getName())
                    .takeoverDepartmentName(takeoverUser.getDepartmentName())
                    .handoverUsername(handoverUserDetail.getName())
                    .handoverDepartmentName(handoverUserDetail.getDepartmentName())
                    .transferTime(new Date())
                    .build();
            this.save(record);
        }
        // 4.???????????????????????? ??????????????????????????????
        List<String> externalUserIdList = unassignedList.stream().filter(a -> handoverUserId.equals(a.getHandover_userid())).map(GetUnassignedListResp.UnassignedInfo::getExternal_userid).collect(Collectors.toList());
        if (StringUtils.isNotBlank(externalUserid)) {
            if (externalUserIdList.contains(externalUserid)) {
                // ???????????????????????????,?????????????????????????????????,?????????????????????,???????????????????????????????????????????????????????????????????????????
                externalUserIdList.clear();
                externalUserIdList.add(externalUserid);
            } else {
                throw new CustomException(ResultTip.TIP_CUSTOMER_CANNOT_BE_ASSIGNED);
            }
        }
        if (CollectionUtils.isNotEmpty(externalUserIdList)) {
            this.transferCustomer(corpId, handoverUserId, takeoverUserId, externalUserIdList, record.getId());
        }
        // 5. ???????????????
        this.transferGroup(corpId, handoverUserId, takeoverUserId, chatId, record.getId());
    }

    @Override
    public void transferGroup(String corpId, String handoverUserId, String takeoverUserId, String chatId, Long recordId) {
        if (StringUtils.isAnyBlank(corpId, handoverUserId, takeoverUserId) || recordId == null) {
            throw new CustomException(ResultTip.TIP_GENERAL_PARAM_ERROR);
        }
        // 1. ?????????????????????????????????????????????????????????( ??????API??????????????????,?????? ?????????????????????????????? )
        GroupChatListReq groupReq = GroupChatListReq.builder()
                .status_filter(GroupConstants.OWNER_LEAVE)
                .build();
        groupReq.setOwnerFilter(handoverUserId);
        GroupChatListResp groupResp = (GroupChatListResp) groupReq.executeTillNoNextPage(corpId);
        if (CollectionUtils.isEmpty(groupResp.getTotalList())) {
            log.info("???????????????????????????????????????????????????,corpId:{},handoverUserId:{},takeoverUserId:{}", corpId, handoverUserId, takeoverUserId);
            return;
        }
        List<String> chatIdList = groupResp.getChatIdList();
        if (StringUtils.isNotBlank(chatId)) {
            // ?????????????????????????????????????????????,??????????????????????????????api?????????????????????????????????????????????
            if (chatIdList.contains(chatId)) {
                chatIdList.clear();
                chatIdList.add(chatId);
            } else {
                throw new CustomException(ResultTip.TIP_GROUP_CANNOT_BE_ASSIGNED);
            }
        }
        // 2. ?????????????????????????????????
        TransferResignedGroupChatReq transferReq = TransferResignedGroupChatReq.builder()
                .chat_id_list(chatIdList)
                .new_owner(takeoverUserId)
                .build();
        TransferResignedGroupChatResp transferResp = transferReq.execute(corpId);
        // 3. ?????????????????????????????????,???????????????
        List<WeResignedGroupTransferRecord> recordList = transferResp.getRecordList(recordId, chatIdList);
        if (CollectionUtils.isNotEmpty(recordList)) {
            BatchInsertUtil.doInsert(recordList, weResignedGroupTransferRecordMapper::batchInsert);
        }
        // 4. ???????????????????????????????????????
        List<WeGroup> updateGroupList = transferResp.getSuccessTransferGroup(chatIdList, takeoverUserId);
        weGroupService.saveOrUpdateBatch(updateGroupList);

    }

    @Override
    public void transferCustomer(String corpId, String handoverUserId, String takeoverUserId, List<String> externalUserIdList, Long recordId) {
        if (StringUtils.isAnyBlank(corpId, takeoverUserId, handoverUserId) || recordId == null || CollectionUtils.isEmpty(externalUserIdList)) {
            throw new CustomException(ResultTip.TIP_GENERAL_PARAM_ERROR);
        }
        // 1. ??????[???????????????????????????]API
        TransferResignedCustomerReq req = TransferResignedCustomerReq.builder()
                .handover_userid(handoverUserId)
                .takeover_userid(takeoverUserId)
                .external_userid(externalUserIdList)
                .build();
        TransferResignedCustomerResp resp = req.execute(corpId);
        if (CollectionUtils.isEmpty(resp.getCustomer())) {
            return;
        }
        // 2. ????????????????????????
        List<WeResignedCustomerTransferRecord> recordList = resp.getRecordList(recordId);
        if (CollectionUtils.isNotEmpty(recordList)) {
            BatchInsertUtil.doInsert(recordList, weResignedTransferRecordMapper::batchInsert);
        }
        // 3.?????????????????????????????????,??????????????? ????????????????????????
        List<WeFlowerCustomerRel> relList = new ArrayList<>();
        for (WeResignedCustomerTransferRecord record : recordList) {
            if (CustomerTransferStatusEnum.WAIT.getType().equals(record.getStatus())) {
                relList.add(
                        WeFlowerCustomerRel.builder()
                                .id(SnowFlakeUtil.nextId())
                                .externalUserid(record.getExternalUserid())
                                .userId(handoverUserId)
                                .corpId(corpId)
                                .status(CustomerStatusEnum.TRANSFERRING.getCode().toString())
                                .build()
                );
            }
        }
        if (CollectionUtils.isNotEmpty(relList)) {
            BatchInsertUtil.doInsert(relList, weFlowerCustomerRelService::batchUpdateStatus);
        }
    }


    @Override
    public List<GetUnassignedListResp.UnassignedInfo> getUnassignedList(String corpId) {
        if (StringUtils.isBlank(corpId)) {
            throw new CustomException(ResultTip.TIP_GENERAL_PARAM_ERROR);
        }
        GetUnassignedListReq req = new GetUnassignedListReq();
        GetUnassignedListResp resp = (GetUnassignedListResp) req.executeTillNoNextPage(corpId);
        if (resp == null || CollectionUtils.isEmpty(resp.getTotalList())) {
            log.info("[????????????]?????????????????????????????????corpId:{}", corpId);
            return Collections.emptyList();
        }
        return resp.getTotalList();
    }

    @Override
    @DataScope
    public List<TransferResignedUserVO> listOfRecord(TransferResignedUserListDTO dto) {
        if (StringUtils.isBlank(dto.getCorpId())) {
            throw new CustomException(ResultTip.TIP_GENERAL_PARAM_ERROR);
        }
        return weResignedTransferRecordMapper.listOfRecord(dto);
    }

    @Override
    public void handleTransferFail(String corpId, String userId, String externalUserId, String failReason) {
        if (StringUtils.isAnyBlank(corpId, userId, externalUserId, failReason)) {
            log.warn("[????????????]??????????????????,????????????,corpId:{},userId:{},externalUserId:{},failReason:{}", corpId, userId, externalUserId, failReason);
            return;
        }
        // 1. ??????????????????????????????
        WeFlowerCustomerRel rel = weFlowerCustomerRelService.getOne(new LambdaQueryWrapper<WeFlowerCustomerRel>()
                .eq(WeFlowerCustomerRel::getExternalUserid, externalUserId)
                .eq(WeFlowerCustomerRel::getUserId, userId)
                .eq(WeFlowerCustomerRel::getCorpId, corpId)
                .last(GenConstants.LIMIT_1)
        );
        if (rel != null) {
            rel.setStatus(CustomerStatusEnum.DRAIN.getCode().toString());
            weFlowerCustomerRelService.updateById(rel);
        } else {
            log.info("[????????????]??????????????????,??????????????????????????????,corpId:{},userId:{},externalUserId:{},failReason:{}", corpId, userId, externalUserId, failReason);
        }
        // 2. ??????????????????????????????????????????,???????????????
        WeResignedCustomerTransferRecord record = weResignedCustomerTransferRecordMapper.getByHandoverUserAndExternalUser(corpId, userId, externalUserId);
        if (record != null) {
            CustomerTransferStatusEnum failResult = CustomerTransferStatusEnum.getByFailReason(failReason);
            if (failResult == null) {
                log.info("[????????????]??????????????????,corpId:{},userId:{},externalUserId:{},failReason:{}", corpId, userId, externalUserId, failReason);
                return;
            }
            record.setStatus(failResult.getType());
            record.setRemark(failResult.getDescribeType());
            weResignedCustomerTransferRecordMapper.updateRecord(record);
        } else {
            log.info("[????????????]??????????????????,????????????????????????????????????,corpId:{},userId:{},externalUserId:{},failReason:{}", corpId, userId, externalUserId, failReason);
        }
    }

    @Override
    public List<GetResignedTransferCustomerDetailVO> listOfCustomerRecord(GetResignedTransferDetailDTO dto) {
        if (StringUtils.isAnyBlank(dto.getCorpId())) {
            throw new CustomException(ResultTip.TIP_GENERAL_PARAM_ERROR);
        }
        if (dto.getRecordId() == null) {
            // ???????????????,????????????????????????????????????id
            dto.setRecordId(this.getRecord(dto.getCorpId(), dto.getHandoverUserId(), dto.getTakeOverUserId(), dto.getDimissionTime()).getId());
        }
        return weResignedCustomerTransferRecordMapper.listOfCustomerRecord(dto);
    }

    @Override
    public List<GetResignedTransferGroupDetailVO> listOfGroupRecord(GetResignedTransferDetailDTO dto) {
        if (StringUtils.isAnyBlank(dto.getCorpId())) {
            throw new CustomException(ResultTip.TIP_GENERAL_PARAM_ERROR);
        }
        if(dto.getRecordId() == null) {
            // ???????????????,????????????????????????????????????id
            dto.setRecordId(this.getRecord(dto.getCorpId(), dto.getHandoverUserId(), dto.getTakeOverUserId(), dto.getDimissionTime()).getId());
        }
        return weResignedGroupTransferRecordMapper.listOfGroupRecord(dto);
    }

    @Override
    public WeResignedTransferRecord getRecord(String corpId, String handoverUserId, String takeoverUserId, Date dimissionTime) {
        if (StringUtils.isAnyBlank(corpId, handoverUserId, takeoverUserId) || dimissionTime == null) {
            throw new CustomException(ResultTip.TIP_GENERAL_PARAM_ERROR);
        }
        WeResignedTransferRecord record = this.getOne(new LambdaQueryWrapper<WeResignedTransferRecord>()
                .eq(WeResignedTransferRecord::getCorpId, corpId)
                .eq(WeResignedTransferRecord::getDimissionTime, dimissionTime)
                .eq(WeResignedTransferRecord::getHandoverUserid, handoverUserId)
                .eq(WeResignedTransferRecord::getTakeoverUserid, takeoverUserId)
                .last(GenConstants.LIMIT_1)
        );
        if (record == null || record.getId() == null) {
            throw new CustomException(ResultTip.TIP_CANNOT_FIND_TRANSFER_RECORD);
        }
        return record;
    }


}