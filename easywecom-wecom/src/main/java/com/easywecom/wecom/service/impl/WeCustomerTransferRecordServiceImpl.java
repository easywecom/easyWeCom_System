package com.easywecom.wecom.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.easywecom.common.annotation.DataScope;
import com.easywecom.common.constant.GenConstants;
import com.easywecom.common.constant.WeConstans;
import com.easywecom.common.core.domain.wecom.WeUser;
import com.easywecom.common.enums.CustomerStatusEnum;
import com.easywecom.common.enums.CustomerTransferStatusEnum;
import com.easywecom.common.enums.ResultTip;
import com.easywecom.common.enums.StaffActivateEnum;
import com.easywecom.common.exception.CustomException;
import com.easywecom.wecom.client.WeExternalContactClient;
import com.easywecom.wecom.domain.WeCustomer;
import com.easywecom.wecom.domain.WeFlowerCustomerRel;
import com.easywecom.wecom.domain.dto.transfer.TransferCustomerDTO;
import com.easywecom.wecom.domain.dto.transfer.TransferCustomerReq;
import com.easywecom.wecom.domain.dto.transfer.TransferCustomerResp;
import com.easywecom.wecom.domain.dto.transfer.TransferRecordPageDTO;
import com.easywecom.wecom.domain.entity.transfer.WeCustomerTransferRecord;
import com.easywecom.wecom.domain.vo.customer.WeCustomerVO;
import com.easywecom.wecom.domain.vo.transfer.WeCustomerTransferRecordVO;
import com.easywecom.wecom.mapper.WeCustomerMapper;
import com.easywecom.wecom.mapper.WeCustomerTransferRecordMapper;
import com.easywecom.wecom.service.WeCustomerTransferRecordService;
import com.easywecom.wecom.service.WeFlowerCustomerRelService;
import com.easywecom.wecom.service.WeUserService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.validation.constraints.NotNull;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ??????: ???????????????????????????????????????????????????
 *
 * @author : silver_chariot
 * @date : 2021/11/29 17:54
 */
@Service
@Slf4j
public class WeCustomerTransferRecordServiceImpl extends ServiceImpl<WeCustomerTransferRecordMapper, WeCustomerTransferRecord> implements WeCustomerTransferRecordService {

    private final WeCustomerTransferRecordMapper weCustomerTransferRecordMapper;
    private final WeUserService weUserService;
    private final WeExternalContactClient externalContactClient;
    private final WeFlowerCustomerRelService weFlowerCustomerRelService;
    private final WeCustomerMapper weCustomerMapper;

    @Autowired
    public WeCustomerTransferRecordServiceImpl(@NotNull WeCustomerTransferRecordMapper weCustomerTransferRecordMapper, @NotNull WeUserService weUserService,
                                               @NotNull WeExternalContactClient externalContactClient, @NotNull WeFlowerCustomerRelService weFlowerCustomerRelService,
                                               @NotNull WeCustomerMapper weCustomerMapper) {
        this.weCustomerTransferRecordMapper = weCustomerTransferRecordMapper;
        this.weUserService = weUserService;
        this.externalContactClient = externalContactClient;
        this.weFlowerCustomerRelService = weFlowerCustomerRelService;
        this.weCustomerMapper = weCustomerMapper;
    }

    @Override
    public void transfer(String corpId, TransferCustomerDTO.HandoverCustomer[] transferList, String takeoverUserId, String transferSuccessMsg) {
        if (ArrayUtils.isEmpty(transferList) || StringUtils.isBlank(corpId)) {
            throw new CustomException(ResultTip.TIP_GENERAL_PARAM_ERROR);
        }
        // 1. ???????????????????????????????????????
        WeUser takeoverUser = weUserService.getUserDetail(corpId, takeoverUserId);
        if (takeoverUser == null || !StaffActivateEnum.ACTIVE.getCode().equals(takeoverUser.getIsActivate())) {
            throw new CustomException(ResultTip.TIP_USER_NOT_ACTIVE);
        }
        // 2. ????????????????????????????????????????????????
        this.validateTakeoverUser(transferList,takeoverUserId);
        // 3. ????????????????????????(?????????????????????????????????????????????????????????????????????????????????,??????????????????????????????????????????API?????????)
        Map<String, List<TransferCustomerDTO.HandoverCustomer>> map = Arrays.stream(transferList)
                .collect(Collectors.groupingBy(TransferCustomerDTO.HandoverCustomer::getHandoverUserid));
        // 4. ?????????????????? ?????????????????????????????????????????????
        for (Map.Entry<String, List<TransferCustomerDTO.HandoverCustomer>> entry : map.entrySet()) {
            String handoverUserId = entry.getKey();
            WeUser handoverUser = weUserService.getUserDetail(corpId, handoverUserId);
            List<String> externalUserIds = entry.getValue().stream().map(TransferCustomerDTO.HandoverCustomer::getExternalUserid).collect(Collectors.toList());
            this.doTransfer(corpId, handoverUser, takeoverUser, externalUserIds, transferSuccessMsg);
        }
    }

    /**
     * ???????????????
     *
     * @param transferList ???????????????
     * @param takeoverUserId ?????????id
     */
    private void validateTakeoverUser(TransferCustomerDTO.HandoverCustomer[] transferList, String takeoverUserId) {
        if(ArrayUtils.isEmpty(transferList) || StringUtils.isBlank(takeoverUserId)) {
            throw new CustomException(ResultTip.TIP_GENERAL_PARAM_ERROR);
        }
        List<String> list = Arrays.asList(transferList).stream().map(TransferCustomerDTO.HandoverCustomer::getHandoverUserid).collect(Collectors.toList());
        if(list.contains(takeoverUserId)) {
            throw new CustomException(ResultTip.TIP_CAN_TRANSFER_SELF_CUSTOMER);
        }
    }

    @Override
    public void doTransfer(String corpId, WeUser handoverUser, WeUser takeoverUser, List<String> externalUserIds, String transferSuccessMsg) {
        if (StringUtils.isBlank(corpId) || handoverUser == null || takeoverUser == null || CollectionUtils.isEmpty(externalUserIds)) {
            throw new CustomException(ResultTip.TIP_GENERAL_PARAM_ERROR);
        }
        // 1. ????????????,????????????API
        TransferCustomerReq req = TransferCustomerReq.builder()
                .handover_userid(handoverUser.getUserId())
                .takeover_userid(takeoverUser.getUserId())
                .external_userid(externalUserIds.toArray(new String[]{}))
                .transfer_success_msg(transferSuccessMsg)
                .build();
        TransferCustomerResp resp = externalContactClient.transferCustomer(req, corpId);
        // 2. ??????????????????
        List<WeCustomerTransferRecord> recordList = this.buildRecord(corpId, handoverUser, takeoverUser, externalUserIds);
        // 3. ??????API?????????????????? ???????????????????????????????????????????????????????????????id??????
        List<String> transferSuccessExternalUserList = resp.handleFailRecord(recordList);
        // 4. ????????????????????????????????????, ??????????????????????????????????????????
        if (CollectionUtils.isNotEmpty(recordList)) {
            this.saveBatch(recordList);
        }
        if (CollectionUtils.isNotEmpty(transferSuccessExternalUserList)) {
            // ??????????????????
            WeFlowerCustomerRel entity = new WeFlowerCustomerRel();
            entity.setStatus(CustomerStatusEnum.TRANSFERRING.getCode().toString());
            weFlowerCustomerRelService.update(entity, new LambdaUpdateWrapper<WeFlowerCustomerRel>()
                    .eq(WeFlowerCustomerRel::getCorpId, corpId)
                    .eq(WeFlowerCustomerRel::getUserId, handoverUser.getUserId())
                    .in(WeFlowerCustomerRel::getExternalUserid, externalUserIds)
            );
        }
    }

    @Override
    public void batchUpdate(List<WeCustomerTransferRecord> totalList) {
        weCustomerTransferRecordMapper.batchUpdate(totalList);
    }

    @Override
    @DataScope
    public List<WeCustomerVO> transferCustomerList(WeCustomer weCustomer) {
        if (StringUtils.isBlank(weCustomer.getCorpId())) {
            throw new CustomException(ResultTip.TIP_GENERAL_PARAM_ERROR);
        }
        // ??????????????????
        List<WeCustomerVO> list = weCustomerMapper.selectWeCustomerListV2(weCustomer);
        // ??????????????????
        List<WeCustomerTransferRecord> recordList = this.list(new LambdaQueryWrapper<WeCustomerTransferRecord>()
                .eq(WeCustomerTransferRecord::getCorpId, weCustomer.getCorpId())
                .in(WeCustomerTransferRecord::getStatus, CustomerTransferStatusEnum.getTransferAvailTypes())
        );
        // ??????????????????,?????? ????????????-> ???????????????????????????
        Map<WeFlowerCustomerRel, Integer> map = recordList.stream()
                .collect(Collectors.toMap(WeFlowerCustomerRel::new, WeCustomerTransferRecord::getStatus, (oldValue, newValue) -> newValue));
        for (WeCustomerVO customer : list) {
            WeFlowerCustomerRel rel = new WeFlowerCustomerRel(customer);
            // ????????????????????????????????????,????????????????????????
            if (map.containsKey(rel)) {
                customer.setTransferStatus(map.get(rel));
            }
        }
        return list;
    }

    @Override
    @DataScope
    public List<WeCustomerTransferRecordVO> getList(TransferRecordPageDTO dto) {
        if (StringUtils.isBlank(dto.getCorpId())) {
            throw new CustomException(ResultTip.TIP_GENERAL_PARAM_ERROR);
        }
        return weCustomerTransferRecordMapper.getList(dto);
    }

    @Override
    public void handleTransferFail(String corpId, String userId, String externalUserId, String failReason) {
        if (StringUtils.isAnyBlank(corpId, userId, externalUserId, failReason)) {
            log.error("[????????????????????????]????????????,corpId:{},userId:{},externalUserId:{},failReason:{}", corpId, userId, externalUserId, failReason);
            return;
        }
        // ???????????????????????????,?????????????????????
        WeCustomerTransferRecord weCustomerTransferRecord = new WeCustomerTransferRecord();
        CustomerTransferStatusEnum transferStatus = CustomerTransferStatusEnum.getByFailReason(failReason);
        if (transferStatus == null) {
            log.info("??????????????????,corpId:{},userId:{},externalUserId:{},failReason:{}", corpId, userId, externalUserId, failReason);
            return;
        }
        weCustomerTransferRecord.setStatus(transferStatus.getType());
        weCustomerTransferRecord.setRemark(transferStatus.getDescribeType());
        this.update(weCustomerTransferRecord, new LambdaUpdateWrapper<WeCustomerTransferRecord>()
                .eq(WeCustomerTransferRecord::getCorpId, corpId)
                .eq(WeCustomerTransferRecord::getHandoverUserid, userId)
                .eq(WeCustomerTransferRecord::getExternalUserid, externalUserId)
        );
        // ???????????????????????????????????????
        WeFlowerCustomerRel rel = new WeFlowerCustomerRel();
        rel.setStatus(String.valueOf(CustomerStatusEnum.NORMAL.getCode()));
        weFlowerCustomerRelService.update(rel, new LambdaUpdateWrapper<WeFlowerCustomerRel>()
                .eq(WeFlowerCustomerRel::getExternalUserid, externalUserId)
                .eq(WeFlowerCustomerRel::getUserId, userId)
                .eq(WeFlowerCustomerRel::getCorpId, corpId)
        );
    }

    @Override
    public void handleTransferSuccess(String corpId, String userId, String externalUserId) {
        if (StringUtils.isAnyBlank(corpId, userId, externalUserId)) {
            log.error("[????????????????????????]????????????,corpId:{},userId:{},externalUserId:{}", corpId, userId, externalUserId);
            return;
        }
        // 1. ??????????????????
        WeCustomerTransferRecord record = this.getOne(new LambdaQueryWrapper<WeCustomerTransferRecord>()
                .eq(WeCustomerTransferRecord::getCorpId, corpId)
                .eq(WeCustomerTransferRecord::getExternalUserid, externalUserId)
                .eq(WeCustomerTransferRecord::getHandoverUserid, userId)
                .last(GenConstants.LIMIT_1)
        );
        if (record == null || StringUtils.isBlank(record.getTakeoverUserid())) {
            log.info("[????????????????????????]????????????????????????????????????????????????,corpId:{},userId:{},externalUserId:{},record:{}", corpId, userId, externalUserId, record);
            return;
        }
        // 2. ????????????( ????????????,????????????,????????????)
        weFlowerCustomerRelService.transferCustomerRel(corpId, record.getHandoverUserid(), record.getTakeoverUserid(), externalUserId);
        // 3. ?????????????????????????????????,????????????????????????
        record.setStatus(CustomerTransferStatusEnum.SUCCEED.getType());
        record.setRemark(StringUtils.EMPTY);
        this.updateById(record);
        log.info("[????????????]??????????????????,record:{}", record);
    }


    private List<WeCustomerTransferRecord> buildRecord(String corpId, WeUser handoverUser, WeUser takeoverUser, List<String> externalUserIds) {
        if (StringUtils.isBlank(corpId) || handoverUser == null || takeoverUser == null || CollectionUtils.isEmpty(externalUserIds)) {
            throw new CustomException(ResultTip.TIP_GENERAL_PARAM_ERROR);
        }
        List<WeCustomerTransferRecord> recordList = new ArrayList<>();
        Date transferTime = new Date();
        for (String externalUserId : externalUserIds) {
            recordList.add(
                    WeCustomerTransferRecord.builder()
                            .corpId(corpId)
                            .handoverUserid(handoverUser.getUserId())
                            .externalUserid(externalUserId)
                            .takeoverUserid(takeoverUser.getUserId())
                            .hanoverUsername(handoverUser.getName())
                            .takeoverUsername(takeoverUser.getName())
                            .handoverDepartmentName(handoverUser.getDepartmentName())
                            .takeoverDepartmentName(takeoverUser.getDepartmentName())
                            .transferTime(transferTime)
                            .status(CustomerTransferStatusEnum.WAIT.getType())
                            .remark(WeConstans.DEFAULT_TRANSFER_NOTICE)
                            .build()
            );
        }
        return recordList;
    }

}
