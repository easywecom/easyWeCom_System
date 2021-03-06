package com.easywecom.wecom.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSONArray;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dtflys.forest.exceptions.ForestRuntimeException;
import com.easywecom.common.annotation.DataScope;
import com.easywecom.common.constant.GenConstants;
import com.easywecom.common.constant.WeConstans;
import com.easywecom.common.core.domain.AjaxResult;
import com.easywecom.common.enums.ResultTip;
import com.easywecom.common.exception.CustomException;
import com.easywecom.common.exception.wecom.WeComException;
import com.easywecom.common.utils.DateUtils;
import com.easywecom.common.utils.bean.BeanUtils;
import com.easywecom.common.utils.poi.ExcelUtil;
import com.easywecom.common.utils.sql.BatchInsertUtil;
import com.easywecom.wecom.client.WeCustomerClient;
import com.easywecom.wecom.client.WeUserClient;
import com.easywecom.wecom.domain.*;
import com.easywecom.wecom.domain.dto.*;
import com.easywecom.wecom.domain.dto.customer.CustomerTagEdit;
import com.easywecom.wecom.domain.dto.customer.EditCustomerDTO;
import com.easywecom.wecom.domain.dto.customer.GetExternalDetailResp;
import com.easywecom.wecom.domain.dto.customer.req.GetByUserReq;
import com.easywecom.wecom.domain.dto.customer.resp.GetByUserResp;
import com.easywecom.wecom.domain.dto.customersop.Column;
import com.easywecom.wecom.domain.dto.pro.EditCustomerFromPlusDTO;
import com.easywecom.wecom.domain.dto.tag.RemoveWeCustomerTagDTO;
import com.easywecom.wecom.domain.entity.WeCustomerExportDTO;
import com.easywecom.wecom.domain.vo.QueryCustomerFromPlusVO;
import com.easywecom.wecom.domain.vo.WeCustomerExportVO;
import com.easywecom.wecom.domain.vo.WeMakeCustomerTagVO;
import com.easywecom.wecom.domain.vo.customer.WeCustomerSumVO;
import com.easywecom.wecom.domain.vo.customer.WeCustomerUserListVO;
import com.easywecom.wecom.domain.vo.customer.WeCustomerVO;
import com.easywecom.wecom.domain.vo.sop.CustomerSopVO;
import com.easywecom.wecom.login.util.LoginTokenService;
import com.easywecom.wecom.mapper.WeCustomerMapper;
import com.easywecom.wecom.service.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.validation.constraints.NotBlank;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ????????? WeCustomerServiceImpl
 *
 * @author ??????
 * @date 2021/8/26 20:28
 */
@Slf4j
@Service
public class WeCustomerServiceImpl extends ServiceImpl<WeCustomerMapper, WeCustomer> implements WeCustomerService {
    @Autowired
    private WeCustomerMapper weCustomerMapper;
    @Autowired
    private WeCustomerClient weCustomerClient;
    @Autowired
    private WeFlowerCustomerRelService weFlowerCustomerRelService;

    @Autowired
    private WeTagGroupService weTagGroupService;

    @Autowired
    private WeUserService weUserService;

    @Autowired
    @Lazy
    private WeFlowerCustomerTagRelService weFlowerCustomerTagRelService;

    @Autowired
    private WeUserClient weUserClient;
    @Autowired
    private WeCustomerTrajectoryService weCustomerTrajectoryService;
    @Autowired
    @Lazy
    private PageHomeService pageHomeService;
    @Autowired
    private WeCustomerExtendPropertyRelService weCustomerExtendPropertyRelService;
    @Autowired
    @Lazy
    private WeCustomerExtendPropertyService weCustomerExtendPropertyService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean saveOrUpdate(WeCustomer weCustomer) {
        if (weCustomer == null) {
            return false;
        }
        if (StringUtils.isNotBlank(weCustomer.getExternalUserid())
                && StringUtils.isNotBlank(weCustomer.getUserId())
                && StringUtils.isNotBlank(weCustomer.getCorpId())) {
            //??????corpId?????????
            WeCustomer weCustomerBean = selectWeCustomerById(weCustomer.getExternalUserid(), weCustomer.getCorpId());
            if (weCustomerBean != null) {
                WeFlowerCustomerRel weFlowerCustomerRel = new WeFlowerCustomerRel();
                weFlowerCustomerRel.setCorpId(weCustomer.getCorpId());
                weFlowerCustomerRel.setRemark(weCustomer.getRemark());
                weFlowerCustomerRel.setUserId(weCustomer.getUserId());
                weFlowerCustomerRel.setRemarkMobiles(weCustomer.getPhone());
                weFlowerCustomerRel.setDescription(weCustomer.getDesc());

                weFlowerCustomerRelService.update(weFlowerCustomerRel, new LambdaQueryWrapper<WeFlowerCustomerRel>()
                        .eq(WeFlowerCustomerRel::getExternalUserid, weCustomer.getExternalUserid())
                        .eq(WeFlowerCustomerRel::getUserId, weCustomer.getUserId())
                        .eq(WeFlowerCustomerRel::getCorpId, weCustomer.getCorpId()));

                return weCustomerMapper.updateWeCustomer(weCustomer) == 1;
            } else {
                return weCustomerMapper.insertWeCustomer(weCustomer) == 1;
            }
        }
        return false;
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public void editCustomer(EditCustomerDTO dto) {
        if (dto == null || StringUtils.isAnyBlank(dto.getExternalUserid(), dto.getUserId(), dto.getCorpId())) {
            throw new CustomException(ResultTip.TIP_GENERAL_PARAM_ERROR);
        }
        String corpId = dto.getCorpId();
        String userId = dto.getUserId();
        String externalUserId = dto.getExternalUserid();
        // 1.??????????????????
        WeCustomer weCustomer = dto.transferToCustomer();
        if (dto.getBirthday() != null) {
            weCustomerMapper.updateBirthday(weCustomer);
        }
        // 2.?????????????????????????????????
        if (CollectionUtils.isNotEmpty(weCustomer.getExtendProperties())) {
            weCustomerExtendPropertyRelService.updateBatch(weCustomer);
        }

        // 3.???????????????????????????????????????
        WeFlowerCustomerRel rel = dto.transferToCustomerRel();
        WeCustomerDTO.WeCustomerRemark editReq = new WeCustomerDTO().new WeCustomerRemark(rel);
        weCustomerClient.remark(editReq, corpId);
        weFlowerCustomerRelService.update(rel, new LambdaUpdateWrapper<WeFlowerCustomerRel>()
                .eq(WeFlowerCustomerRel::getCorpId, corpId)
                .eq(WeFlowerCustomerRel::getExternalUserid, externalUserId)
                .eq(WeFlowerCustomerRel::getUserId, userId)
        );
        // 4.??????????????????
        if (dto.getEditTag() != null) {
            // ??????????????????
            weFlowerCustomerTagRelService.removeByCustomerIdAndUserId(externalUserId, userId, corpId);
            // ????????????????????????
            if (!dto.getEditTag().isEmpty()) {
                this.batchMarkCustomTag(
                        WeMakeCustomerTagVO.builder()
                                .corpId(corpId)
                                .externalUserid(externalUserId)
                                .userId(userId)
                                .addTag(dto.getEditTag())
                                .build()
                );
            }
        }
        // 5.??????????????????(????????????)
        weCustomerTrajectoryService.recordEditOperation(dto);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateWeCustomerRemark(WeCustomer weCustomer) {
        if (weCustomer == null
                || StringUtils.isAnyBlank(weCustomer.getCorpId(), weCustomer.getUserId(), weCustomer.getExternalUserid())) {
            throw new CustomException(ResultTip.TIP_GENERAL_PARAM_ERROR);
        }
        // ?????????????????????
        weCustomerExtendPropertyRelService.updateBatch(weCustomer);
        // ????????????????????????:????????????????????????????????????????????????????????????????????????????????????????????????
        if (weCustomer.getRemark() != null || weCustomer.getPhone() != null || weCustomer.getDesc() != null) {
            //????????????????????????????????????????????????????????????????????????
            WeCustomerDTO.WeCustomerRemark weCustomerRemark = new WeCustomerDTO().new WeCustomerRemark(weCustomer);
            //?????????????????????
            weCustomerClient.remark(weCustomerRemark, weCustomer.getCorpId());
        }
        saveOrUpdate(weCustomer);
    }

    /**
     * ????????????????????????
     *
     * @param externalUserId ??????????????????ID
     * @return ??????????????????
     */
    @Override
    public WeCustomer selectWeCustomerById(String externalUserId, String corpId) {
        if (StringUtils.isAnyBlank(externalUserId, corpId)) {
            log.error("?????????????????????externalUserId = {} , corpId = {}", externalUserId, corpId);
            throw new CustomException("????????????????????????");
        }

        return weCustomerMapper.selectWeCustomerById(externalUserId, corpId);
    }


    @Override
    @DataScope
    public List<WeCustomerVO> selectWeCustomerListV2(WeCustomer weCustomer) {
        if (StringUtils.isBlank(weCustomer.getCorpId())) {
            throw new CustomException(ResultTip.TIP_GENERAL_PARAM_ERROR);
        }
        return weCustomerMapper.selectWeCustomerListV2(weCustomer);
    }

    @Override
    public List<WeCustomer> listOfUseCustomer(String corpId, WeOperationsCenterCustomerSopFilterEntity sopFilter) {
        WeCustomerPushMessageDTO weCustomerPushMessageDTO = new WeCustomerPushMessageDTO();
        weCustomerPushMessageDTO.setCorpId(corpId);
        //-1????????????????????????????????? ??????????????????????????????????????????
        if (!Integer.valueOf(-1).equals(sopFilter.getGender())) {
            weCustomerPushMessageDTO.setGender(sopFilter.getGender());
        }
        weCustomerPushMessageDTO.setTagIds(sopFilter.getTagId());
        weCustomerPushMessageDTO.setCustomerStartTime(sopFilter.getStartTime());
        weCustomerPushMessageDTO.setCustomerEndTime(sopFilter.getEndTime());
        weCustomerPushMessageDTO.setUserIds(sopFilter.getUsers());
        weCustomerPushMessageDTO.setDepartmentIds(sopFilter.getDepartments());
        weCustomerPushMessageDTO.setFilterTags(sopFilter.getFilterTagId());
        //????????????????????????
        List<WeCustomer> weCustomers = selectWeCustomerListNoRel(weCustomerPushMessageDTO);
        if (StringUtils.isNotEmpty(sopFilter.getCloumnInfo())) {
            List<Column> columns = JSONArray.parseArray(sopFilter.getCloumnInfo(), Column.class);
            if (CollectionUtils.isNotEmpty(columns)) {
                //???????????????????????????
                List<String> customers = weCustomerExtendPropertyRelService.listOfPropertyIdAndValue(columns);
                //?????????????????????
                weCustomers = weCustomers.stream().filter(customer -> customers.contains(customer.getExternalUserid())).collect(Collectors.toList());
            }
        }
        return weCustomers;
    }

    /**
     * ???????????????????????????????????????????????????
     *
     * @param weCustomer {@link WeCustomer}
     * @return
     */
    @DataScope
    @Override
    public List<WeCustomerVO> selectWeCustomerListDistinct(WeCustomer weCustomer) {
        if (StringUtils.isBlank(weCustomer.getCorpId())) {
            throw new CustomException(ResultTip.TIP_GENERAL_PARAM_ERROR);
        }
        return weCustomerMapper.selectWeCustomerListDistinct(weCustomer);
    }

    @Override
    public List<WeCustomerUserListVO> listUserListByCustomerId(String customerId, String corpId) {
        if (StringUtils.isAnyBlank(customerId, corpId)) {
            throw new CustomException(ResultTip.TIP_GENERAL_PARAM_ERROR);
        }
        return weCustomerMapper.selectUserListByCustomerId(customerId, corpId);
    }

    @Override
    @DataScope
    public WeCustomerSumVO weCustomerCount(WeCustomer weCustomer) {
        if (StringUtils.isAnyBlank(weCustomer.getCorpId())) {
            throw new CustomException(ResultTip.TIP_GENERAL_PARAM_ERROR);
        }
        List<WeCustomerVO> list = this.selectWeCustomerListV2(weCustomer);
        // ??????externalUserId??????
        Set<String> set = list.stream().map(WeCustomerVO::getExternalUserid).collect(Collectors.toSet());
        return WeCustomerSumVO.builder()
                .totalCount(list.size())
                .ignoreDuplicateCount(set.size())
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Async
    public void syncWeCustomerV2(String corpId) {
        if (StringUtils.isBlank(corpId)) {
            throw new CustomException(ResultTip.TIP_MISS_CORP_ID);
        }
        log.info("??????????????????,corpId:{}", corpId);
        long startTime = System.currentTimeMillis();

        // 1.??????[????????????????????????????????????????????????] ??????API
        List<String> userIdList = weCustomerClient.getFollowUserList(corpId).getFollowerUserIdList();

        // 2.????????????????????????[????????????????????????] ??????API, ?????????????????????
        for (String userId : userIdList) {
            batchGetCustomerDetailAndSyncLocal(corpId, userId);
        }
        long endTime = System.currentTimeMillis();
        log.info("??????????????????[???]:corpId:{}?????????{}", corpId, Double.valueOf((endTime - startTime) / 1000.00D));
        // 3.?????????????????????,?????????????????????????????????
        pageHomeService.getCustomerData(corpId);

    }


    /**
     * ???????????????????????? ??????????????????
     *
     * @param corpId ??????id
     * @param userId ??????id
     */
    public void batchGetCustomerDetailAndSyncLocal(String corpId, String userId) {
        if (StringUtils.isBlank(corpId) || StringUtils.isBlank(userId)) {
            log.info("????????????????????????:????????????,corpId:{},userId:{}", corpId, userId);
            return;
        }
        // 1. API??????:????????????[????????????????????????]??????
        GetByUserReq req = new GetByUserReq(userId);
        GetByUserResp resp = (GetByUserResp) req.executeTillNoNextPage(corpId);

        // 2. ????????????:??????????????????????????????
        resp.handleData(corpId);
        if (resp.isEmptyResult()) {
            return;
        }
        // 3. ????????????:????????????-????????????????????????????????????,???????????????????????????
        weFlowerCustomerRelService.alignData(resp, userId, corpId);
        List<String> externalUserIdList = resp.getCustomerList().stream().map(WeCustomer::getExternalUserid).collect(Collectors.toList());
        List<WeFlowerCustomerRel> localRelList = weFlowerCustomerRelService.list(
                new LambdaQueryWrapper<WeFlowerCustomerRel>()
                        .eq(WeFlowerCustomerRel::getCorpId, corpId)
                        .eq(WeFlowerCustomerRel::getUserId, userId)
                        .in(WeFlowerCustomerRel::getExternalUserid, externalUserIdList)
        );
        // ????????????????????????????????????????????????????????????????????????
        resp.activateDelCustomer(localRelList);
        //**** ??????????????????????????? , ??????-?????????????????? , ??????-??????????????????
        // 4. ????????????:??????????????? ????????????,??????-????????????
        BatchInsertUtil.doInsert(resp.getCustomerList(), this::batchInsert);
        BatchInsertUtil.doInsert(resp.getRelList(), list -> weFlowerCustomerRelService.batchInsert(list));

        // 5. ????????????: ??????-???????????? ,???????????????????????????????????????,????????????????????????
        List<WeFlowerCustomerTagRel> tagRelList = resp.getCustomerTagRelList(localRelList);
        BatchInsertUtil.doInsert(tagRelList, list -> weFlowerCustomerTagRelService.batchInsert(list));
    }


    /**
     * ??????????????????corpId????????????????????????
     *
     * @param corpId ??????id
     * @return ??????????????????
     */
    @Override
    public List<LeaveWeUserListsDTO.LeaveWeUser> getLeaveWeUsers(String corpId) {
        LeaveWeUserListsDTO leaveWeUserListsDTO = new LeaveWeUserListsDTO();
        List<LeaveWeUserListsDTO.LeaveWeUser> result = new ArrayList<>();
        do {
            Map<String, Object> map = new HashMap<>();
            //?????????????????????????????????????????????
            if (StringUtils.isNotBlank(leaveWeUserListsDTO.getNext_cursor())) {
                map.put("cursor", leaveWeUserListsDTO.getNext_cursor());
            }
            //??????????????????
            leaveWeUserListsDTO = weUserClient.leaveWeUsers(map, corpId);

            if (CollUtil.isEmpty(leaveWeUserListsDTO.getInfo())) {
                continue;
            }
            //??????????????????????????????
            result.addAll(leaveWeUserListsDTO.getInfo());
        } while (StringUtils.isNotBlank(leaveWeUserListsDTO.getNext_cursor()));

        return result;
    }

    /**
     * @param leaveWeUsers ??????????????????
     * @return map???????????????id+","+"????????????"??????????????????
     */
    @Override
    public Map<String, List<String>> replaceCustomerListToMap(List<LeaveWeUserListsDTO.LeaveWeUser> leaveWeUsers) {

        if (CollUtil.isEmpty(leaveWeUsers)) {
            return new HashMap<>();
        }
        Map<String, List<String>> map = new HashMap<>(leaveWeUsers.size());

        for (LeaveWeUserListsDTO.LeaveWeUser leaveWeUser : leaveWeUsers) {
            if (map.containsKey(leaveWeUser.getHandover_userid() + "," + leaveWeUser.getDimission_time())) {
                //??????map?????????????????????????????????
                List<String> listExternalUserid = map.get(leaveWeUser.getHandover_userid() + "," + leaveWeUser.getDimission_time());
                listExternalUserid.add(leaveWeUser.getExternal_userid());
                map.put(leaveWeUser.getHandover_userid() + "," + leaveWeUser.getDimission_time(), listExternalUserid);
            } else {
                //?????????????????????id??????
                List<String> listExternalUserid = new ArrayList<>(leaveWeUsers.size());
                listExternalUserid.add(leaveWeUser.getExternal_userid());
                //userId,?????????????????????
                map.put(leaveWeUser.getHandover_userid() + "," + leaveWeUser.getDimission_time(), listExternalUserid);
            }
        }
        return map;
    }

    /**
     * ???????????????
     *
     * @param weMakeCustomerTag ?????????????????????
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void makeLabel(WeMakeCustomerTagVO weMakeCustomerTag) {
        if (StringUtils.isBlank(weMakeCustomerTag.getCorpId())) {
            log.error("????????????????????????corpId????????????");
            throw new CustomException("???????????????");
        }
        List<CustomerTagEdit> customerTagEdits = getCustomerTagEdits(weMakeCustomerTag);
        if (CollUtil.isNotEmpty(customerTagEdits)) {
            //???????????????
            customerTagEdits.forEach(tagEdit -> weCustomerClient.makeCustomerLabel(tagEdit, weMakeCustomerTag.getCorpId()));
        }
    }


    /**
     * ?????????????????????
     *
     * @param list
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void makeLabelbatch(List<WeMakeCustomerTagVO> list, String updateBy) {
        if (CollUtil.isEmpty(list)) {
            return;
        }
        for (WeMakeCustomerTagVO item : list) {
            this.batchMarkCustomTag(item);
            // ???????????????????????????????????????
            if (StringUtils.isNotBlank(updateBy)) {
                weCustomerTrajectoryService.recordEditTagOperation(item.getCorpId(), item.getUserId(), item.getExternalUserid(), updateBy, item.getAddTag());
            }

        }

    }

    @Override
    public void makeLabelbatch(List<WeMakeCustomerTagVO> weMakeCustomerTagVOS) {
        this.makeLabelbatch(weMakeCustomerTagVOS, null);
    }

    /**
     * ?????????????????????
     *
     * @param weMakeCustomerTag ?????????????????????
     * @return
     */
    @Transactional
    public List<CustomerTagEdit> getCustomerTagEdits(WeMakeCustomerTagVO weMakeCustomerTag) {
        if (StringUtils.isBlank(weMakeCustomerTag.getCorpId())) {
            log.error("????????????????????????corpId????????????");
            throw new CustomException("???????????????");
        }
        List<CustomerTagEdit> customerTagEdits = new ArrayList<>();
        //??????????????????????????????
        WeFlowerCustomerRel flowerCustomerRel = weFlowerCustomerRelService.getOne(weMakeCustomerTag.getUserId(), weMakeCustomerTag.getExternalUserid(), weMakeCustomerTag.getCorpId());
        if (flowerCustomerRel != null) {
            List<WeTag> addTags = weMakeCustomerTag.getAddTag();
            //??????????????????
            this.removeAllLabel(flowerCustomerRel);
            if (CollUtil.isNotEmpty(addTags)) {
                addTags.removeAll(Collections.singleton(null));
                List<WeFlowerCustomerTagRel> tagRels = new ArrayList<>();
                //??????????????????
                CustomerTagEdit customerTagEdit = CustomerTagEdit.builder()
                        .userid(flowerCustomerRel.getUserId())
                        .external_userid(flowerCustomerRel.getExternalUserid())
                        .build();
                List<String> tags = new ArrayList<>();
                for (WeTag tag : addTags) {
                    tags.add(tag.getTagId());
                    //??????????????????
                    tagRels.add(
                            WeFlowerCustomerTagRel.builder()
                                    .flowerCustomerRelId(flowerCustomerRel.getId())
                                    .externalUserid(flowerCustomerRel.getExternalUserid())
                                    .tagId(tag.getTagId())
                                    .createTime(new Date())
                                    .build()
                    );
                }

                customerTagEdit.setAdd_tag(ArrayUtil.toArray(tags, String.class));
                customerTagEdits.add(customerTagEdit);
                //?????????????????????
                if (CollUtil.isNotEmpty(tagRels)) {
                    weFlowerCustomerTagRelService.saveOrUpdateBatch(tagRels);
                }
            }
            // ???????????????????????????????????????
            weCustomerTrajectoryService.recordEditTagOperation(weMakeCustomerTag.getCorpId(), weMakeCustomerTag.getUserId(), weMakeCustomerTag.getExternalUserid(),
                    weMakeCustomerTag.getUpdateBy(), weMakeCustomerTag.getAddTag());
        }
        return customerTagEdits;
    }

    /**
     * ??????????????????
     *
     * @param weMakeCustomerTag
     * @return
     */
    private void batchMarkCustomTag(WeMakeCustomerTagVO weMakeCustomerTag) {
        if (StringUtils.isAnyBlank(weMakeCustomerTag.getUserId(), weMakeCustomerTag.getExternalUserid(), weMakeCustomerTag.getCorpId())) {
            log.error("??????id?????????id?????????id???????????????userId???{}???externalUserId???{}???corpId???{}", weMakeCustomerTag.getUserId(), weMakeCustomerTag.getExternalUserid(), weMakeCustomerTag.getCorpId());
            throw new CustomException("????????????????????????");
        }
        //????????????
        List<WeTag> addTags = weMakeCustomerTag.getAddTag();
        if (CollUtil.isEmpty(addTags)) {
            return;
        }
        log.info("????????????????????????: {}", addTags.stream().map(WeTag::getTagId).collect(Collectors.toList()));

        //??????????????????????????????
        WeFlowerCustomerRel flowerCustomerRel = weFlowerCustomerRelService.getOne(weMakeCustomerTag.getUserId(), weMakeCustomerTag.getExternalUserid(), weMakeCustomerTag.getCorpId());

        if (flowerCustomerRel == null) {
            return;
        }
        //???????????????
        addTags.removeAll(Collections.singleton(null));
        if (CollUtil.isEmpty(addTags)) {
            return;
        }

        //????????????????????????
        LambdaQueryWrapper<WeFlowerCustomerTagRel> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WeFlowerCustomerTagRel::getFlowerCustomerRelId, flowerCustomerRel.getId());
        List<WeFlowerCustomerTagRel> weFlowerCustomerTagRelList = weFlowerCustomerTagRelService.list(queryWrapper);
        Set<String> existTagSet = weFlowerCustomerTagRelList.stream().map(WeFlowerCustomerTagRel::getTagId).collect(Collectors.toSet());

        List<WeFlowerCustomerTagRel> tagRels = new ArrayList<>();
        List<String> tags = new ArrayList<>();
        for (WeTag tag : addTags) {
            //?????????????????????????????????????????????
            if (existTagSet.contains(tag.getTagId())) {
                continue;
            }
            tags.add(tag.getTagId());
            //??????????????????
            tagRels.add(
                    WeFlowerCustomerTagRel.builder()
                            .flowerCustomerRelId(flowerCustomerRel.getId())
                            .externalUserid(flowerCustomerRel.getExternalUserid())
                            .tagId(tag.getTagId())
                            .createTime(new Date())
                            .build()
            );
        }
        //?????????????????????
        if (CollUtil.isNotEmpty(tagRels)) {
            weFlowerCustomerTagRelService.saveOrUpdateBatch(tagRels);
        }

        //??????????????????
        log.info("???????????????id??????: {}", tags);
        if (CollUtil.isNotEmpty(tags)) {
            CustomerTagEdit customerTagEdit = CustomerTagEdit.builder()
                    .userid(flowerCustomerRel.getUserId())
                    .external_userid(flowerCustomerRel.getExternalUserid())
                    .build();
            customerTagEdit.setAdd_tag(ArrayUtil.toArray(tags, String.class));
            //???????????????
            weCustomerClient.makeCustomerLabel(customerTagEdit, weMakeCustomerTag.getCorpId());
        }

    }

    /**
     * ????????????????????????
     *
     * @param flowerCustomerRel ????????????
     */
    @Transactional
    public void removeAllLabel(WeFlowerCustomerRel flowerCustomerRel) {
        if (StringUtils.isBlank(flowerCustomerRel.getCorpId())) {
            log.error("??????id?????????id?????????id???????????????corpId???{}", flowerCustomerRel.getCorpId());
            throw new CustomException("????????????????????????");
        }
        //??????????????????
        LambdaQueryWrapper<WeFlowerCustomerTagRel> queryWrapper = new LambdaQueryWrapper<WeFlowerCustomerTagRel>()
                .eq(WeFlowerCustomerTagRel::getFlowerCustomerRelId, flowerCustomerRel.getId());
        List<WeFlowerCustomerTagRel> removeTag = weFlowerCustomerTagRelService.list(queryWrapper);

        //??????????????????
        if (!CollectionUtils.isEmpty(removeTag) && weFlowerCustomerTagRelService.remove(queryWrapper)) {
            //???????????????
            weCustomerClient.makeCustomerLabel(
                    CustomerTagEdit.builder()
                            .external_userid(flowerCustomerRel.getExternalUserid())
                            .userid(flowerCustomerRel.getUserId())
                            .remove_tag(ArrayUtil.toArray(removeTag.stream().map(WeFlowerCustomerTagRel::getTagId).collect(Collectors.toList()), String.class))
                            .build(), flowerCustomerRel.getCorpId());
        }
    }

    @Override
    public void removeLabel(String corpId, String externalUserid, String userid, List<String> delIdList) {
        RemoveWeCustomerTagDTO dto = new RemoveWeCustomerTagDTO();
        List<RemoveWeCustomerTagDTO.WeUserCustomer> list = new ArrayList<>();
        list.add(new RemoveWeCustomerTagDTO.WeUserCustomer(externalUserid, userid, corpId));
        dto.setCustomerList(list);
        dto.setWeTagIdList(delIdList);
        this.removeLabel(dto);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeLabel(RemoveWeCustomerTagDTO removeWeCustomerTagDTO) {
        List<String> removeTagList = removeWeCustomerTagDTO.getWeTagIdList();
        List<RemoveWeCustomerTagDTO.WeUserCustomer> userCustomerList = removeWeCustomerTagDTO.getCustomerList();
        if (CollUtil.isEmpty(removeTagList) || CollUtil.isEmpty(userCustomerList)) {
            throw new WeComException("????????????????????????????????????????????????");
        }

        // ????????????API?????????????????????????????????????????????????????????(??????????????????????????????????????????,??????????????????)
        for (RemoveWeCustomerTagDTO.WeUserCustomer userCustomer : userCustomerList) {
            if (StringUtils.isBlank(userCustomer.getCorpId())) {
                log.error("??????????????????,??????id????????????", userCustomer.getCorpId());
                throw new CustomException("??????????????????");
            }

            CustomerTagEdit customerTagEdit = CustomerTagEdit.builder()
                    .external_userid(userCustomer.getExternalUserid())
                    .userid(userCustomer.getUserId())
                    .remove_tag(ArrayUtil.toArray(removeTagList, String.class))
                    .build();
            //???????????????
            try {
                WeResultDTO response = weCustomerClient.makeCustomerLabel(customerTagEdit, userCustomer.getCorpId());
                if (response != null && response.isSuccess()) {
                    weFlowerCustomerTagRelService.removeByCustomerIdAndUserId(userCustomer.getExternalUserid(), userCustomer.getUserId(), userCustomer.getCorpId(), removeTagList);
                }
            } catch (ForestRuntimeException e) {
                log.error("???????????????????????? e:{}", ExceptionUtils.getStackTrace(e));
            }
        }

    }


    @Override
    public WeCustomerVO getCustomerByUserId(String externalUserid, String userId, String corpId) {
        List<WeCustomerVO> list = getCustomersByUserIdV2(externalUserid, userId, corpId);
        if (CollectionUtils.isEmpty(list)) {
            throw new CustomException(ResultTip.TIP_CUSTOMER_NOT_EXIST);
        }
        return list.get(0);
    }


    @Override
    public List<WeCustomerVO> getCustomersByUserIdV2(String externalUserid, String userId, String corpId) {
        if (StringUtils.isAnyBlank(externalUserid, userId, corpId)) {
            throw new CustomException(ResultTip.TIP_GENERAL_PARAM_ERROR);
        }
        return weCustomerMapper.getCustomersByUserIdV2(externalUserid, userId, corpId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateExternalContactV2(String corpId, String userId, String externalUserid) {
        if (StringUtils.isAnyBlank(corpId, userId, externalUserid)) {
            log.info("????????????,????????????,corpId:{},userId:{},externalUserid:{}", corpId, userId, externalUserid);
            return;
        }
        // 1. ????????????: ????????????API ???????????????????????????
        GetExternalDetailResp resp = weCustomerClient.getV2(externalUserid, corpId);
        if (resp.isEmptyResult()) {
            return;
        }
        // 2. ????????????: ???????????????????????????????????????????????????
        resp.handleData(corpId, userId);
        if (resp.getRemoteRel() != null) {
            weFlowerCustomerRelService.insert(resp.getRemoteRel());
        }
        WeFlowerCustomerRel localRel = weFlowerCustomerRelService.getOne(new LambdaQueryWrapper<WeFlowerCustomerRel>()
                .eq(WeFlowerCustomerRel::getCorpId, corpId)
                .eq(WeFlowerCustomerRel::getExternalUserid, externalUserid)
                .eq(WeFlowerCustomerRel::getUserId, userId)
                .last(GenConstants.LIMIT_1)
        );

        // ????????????????????????????????????????????????????????? ????????????????????????
        List<WeFlowerCustomerTagRel> tagRelList = resp.getTagRelList(localRel);

        // 3. ?????????????????????/?????????????????????????????????,??????????????????
        this.insert(resp.getRemoteCustomer());

        // 4. ?????????????????????????????????????????????,????????????????????????
        weFlowerCustomerTagRelService.remove(new LambdaQueryWrapper<WeFlowerCustomerTagRel>()
                .eq(WeFlowerCustomerTagRel::getFlowerCustomerRelId, localRel.getId())
        );
        if (CollectionUtils.isNotEmpty(tagRelList)) {
            BatchInsertUtil.doInsert(tagRelList, list -> weFlowerCustomerTagRelService.batchInsert(list));
        }
    }

    /**
     * ?????????????????????????????????????????????
     *
     * @param weWelcomeMsg ?????????
     * @param corpId       ??????id
     */
    @Override
    public void sendWelcomeMsg(WeWelcomeMsg weWelcomeMsg, String corpId) {
        weCustomerClient.sendWelcomeMsg(weWelcomeMsg, corpId);
    }


    @Override
    public WeCustomerPortrait findCustomerByOperUseridAndCustomerId(String externalUserid, String userid, String corpId) {
        if (StringUtils.isAnyBlank(externalUserid, userid, corpId)) {
            throw new CustomException(ResultTip.TIP_GENERAL_PARAM_ERROR);
        }
        // ??????????????????
        WeCustomerVO customer = getCustomerByUserId(externalUserid, userid, corpId);
        if (customer == null) {
            throw new CustomException(ResultTip.TIP_CUSTOMER_NOT_EXIST);
        }
        // ???????????????????????????
        WeCustomerPortrait weCustomerPortrait = new WeCustomerPortrait(customer);
        // ????????????
        if (weCustomerPortrait.getBirthday() != null) {
            weCustomerPortrait.setAge(DateUtils.getAge(weCustomerPortrait.getBirthday()));
        }
        //?????????????????????????????????
        weCustomerPortrait.setWeTagGroupList(
                weTagGroupService.findCustomerTagByFlowerCustomerRelId(weCustomerPortrait.getFlowerCustomerRelId())
        );
        //??????????????????
        weCustomerPortrait.setSocialConn(
                this.baseMapper.countSocialConn(externalUserid, userid, corpId)
        );
        return weCustomerPortrait;
    }


    @Override
    @DataScope
    public List<WeCustomer> selectWeCustomerListNoRel(WeCustomerPushMessageDTO weCustomer) {
        if (StringUtils.isBlank(weCustomer.getCorpId())) {
            log.error("???????????????????????????corpId????????????");
            throw new CustomException("????????????????????????");
        }
        WeCustomerPushMessageDTO buildWeCustomer = weCustomer;
        //?????????????????????
        if(StringUtils.isNotEmpty(weCustomer.getDepartmentIds())){
            List<String> userIdsByDepartment = weUserService.listOfUserId(weCustomer.getCorpId(),weCustomer.getDepartmentIds().split(StrUtil.COMMA));
            String userIdsFromDepartment = CollectionUtils.isNotEmpty(userIdsByDepartment) ? StringUtils.join(userIdsByDepartment, WeConstans.COMMA) : StringUtils.EMPTY;
            if(StringUtils.isNotEmpty(buildWeCustomer.getUserIds())){
                buildWeCustomer.setUserIds(buildWeCustomer.getUserIds() + StrUtil.COMMA + userIdsFromDepartment);
            }else{
                buildWeCustomer.setUserIds(userIdsFromDepartment);
            }
        }
        return weCustomerMapper.selectWeCustomerListNoRel(buildWeCustomer);
    }

    @Override
    public List<CustomerSopVO> listOfCustomerIdAndUserId(String corpId, String userIds, @NotBlank List<String> customerIds) {
        if (StringUtils.isBlank(corpId)) {
            throw new CustomException(ResultTip.TIP_MISS_CORP_ID);
        }
        return weCustomerMapper.listOfCustomerIdAndUserId(corpId, userIds, customerIds);
    }

    @Override
    public Integer customerCount(String corpId) {
        if (org.apache.commons.lang3.StringUtils.isBlank(corpId)) {
            throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
        }
        return this.getBaseMapper().countCustomerNum(corpId);
    }

    @Override
    public QueryCustomerFromPlusVO getDetailByUserIdAndCustomerAvatar(String corpId, String userId, String avatar) {
        if (org.apache.commons.lang3.StringUtils.isAnyBlank(corpId, userId, avatar)) {
            return null;
        }
        WeCustomer customer = getOne(new LambdaQueryWrapper<WeCustomer>()
                .eq(WeCustomer::getCorpId, corpId)
                .eq(WeCustomer::getAvatar, avatar)
                .last(GenConstants.LIMIT_1)
        );
        if (customer == null) {
            throw new CustomException(ResultTip.TIP_CUSTOMER_NOT_EXIST);
        }
        QueryCustomerFromPlusVO vo = new QueryCustomerFromPlusVO();
        BeanUtils.copyPropertiesASM(customer, vo);
        // ????????????-??????????????????
        WeFlowerCustomerRel rel = weFlowerCustomerRelService.getOne(
                new LambdaQueryWrapper<WeFlowerCustomerRel>()
                        .eq(WeFlowerCustomerRel::getExternalUserid, customer.getExternalUserid())
                        .eq(WeFlowerCustomerRel::getCorpId, corpId)
                        .eq(WeFlowerCustomerRel::getUserId, userId)
                        .last(GenConstants.LIMIT_1)
        );
        if (rel != null) {
            QueryCustomerFromPlusVO.FollowUserInfo followUserInfo = new QueryCustomerFromPlusVO().new FollowUserInfo();
            BeanUtils.copyPropertiesASM(rel, followUserInfo);
            vo.setFollowUserInfo(followUserInfo);
        }
        return vo;
    }

    @Override
    public void editByUserIdAndCustomerAvatar(EditCustomerFromPlusDTO dto) {
        if (org.apache.commons.lang3.StringUtils.isAnyBlank(dto.getCorpId(), dto.getAvatar(), dto.getUserId())) {
            return;
        }
        // ????????????????????????,????????????????????????id
        WeCustomer customer = getOne(new LambdaQueryWrapper<WeCustomer>()
                .eq(WeCustomer::getCorpId, dto.getCorpId())
                .eq(WeCustomer::getAvatar, dto.getAvatar())
                .last(GenConstants.LIMIT_1)
        );
        if (customer == null) {
            throw new CustomException(ResultTip.TIP_CUSTOMER_NOT_EXIST);
        }
        // ??????????????????
        WeCustomer model = new WeCustomer();
        BeanUtils.copyPropertiesASM(dto, model);
        model.setExternalUserid(customer.getExternalUserid());
        // ????????????????????????????????????
        updateWeCustomerRemark(model);
    }

    @Override
    public void batchInsert(List<WeCustomer> customerList) {
        weCustomerMapper.batchInsert(customerList);
    }

    @Override
    public void insert(WeCustomer weCustomer) {
        List<WeCustomer> list = new ArrayList<>();
        list.add(weCustomer);
        this.batchInsert(list);
    }

    @Override
    public <T> AjaxResult<T> export(WeCustomerExportDTO dto) {
        WeCustomer weCustomer = new WeCustomer();
        BeanUtils.copyProperties(dto, weCustomer);
        List<WeCustomerVO> list = this.selectWeCustomerListV2(weCustomer);
        if (CollectionUtils.isEmpty(list)) {
            throw new CustomException(ResultTip.TIP_NO_DATA_TO_EXPORT);
        }
        List<WeCustomerExportVO> exportList = list.stream().map(WeCustomerExportVO::new).collect(Collectors.toList());
        weCustomerExtendPropertyService.setKeyValueMapper(weCustomer.getCorpId(), exportList, dto.getSelectedProperties());
        ExcelUtil<WeCustomerExportVO> util = new ExcelUtil<>(WeCustomerExportVO.class);
        return util.exportExcelV2(exportList, "customer", dto.getSelectedProperties());
    }

    /**
     * ?????????????????? (??????????????????)
     *
     * @param corpId
     * @param customerName
     * @return
     */
    @Override
    public List<WeCustomerVO> getCustomer(String corpId, String customerName) {
        if (StringUtils.isBlank(corpId)) {
            throw new CustomException(ResultTip.TIP_MISS_CORP_ID);
        }
        return this.baseMapper.listCustomers(customerName, corpId);
    }

}
