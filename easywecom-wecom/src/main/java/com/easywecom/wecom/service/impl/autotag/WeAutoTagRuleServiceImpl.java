package com.easywecom.wecom.service.impl.autotag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.easywecom.common.enums.ResultTip;
import com.easywecom.common.enums.autotag.AutoTagLabelTypeEnum;
import com.easywecom.common.exception.CustomException;
import com.easywecom.common.utils.DateUtils;
import com.easywecom.common.utils.StringUtils;
import com.easywecom.wecom.domain.dto.autotag.TagRuleBatchStatusDTO;
import com.easywecom.wecom.domain.dto.autotag.TagRuleDeleteDTO;
import com.easywecom.wecom.domain.dto.autotag.customer.AddCustomerTagRuleDTO;
import com.easywecom.wecom.domain.dto.autotag.customer.UpdateCustomerTagRuleDTO;
import com.easywecom.wecom.domain.dto.autotag.group.AddGroupTagRuleDTO;
import com.easywecom.wecom.domain.dto.autotag.group.UpdateGroupTagRuleDTO;
import com.easywecom.wecom.domain.dto.autotag.keyword.AddKeywordTagRuleDTO;
import com.easywecom.wecom.domain.dto.autotag.keyword.UpdateKeywordTagRuleDTO;
import com.easywecom.wecom.domain.entity.autotag.WeAutoTagCustomerRuleEffectTime;
import com.easywecom.wecom.domain.entity.autotag.WeAutoTagCustomerScene;
import com.easywecom.wecom.domain.entity.autotag.WeAutoTagGroupScene;
import com.easywecom.wecom.domain.entity.autotag.WeAutoTagRule;
import com.easywecom.wecom.domain.query.autotag.RuleInfoQuery;
import com.easywecom.wecom.domain.query.autotag.TagRuleQuery;
import com.easywecom.wecom.domain.vo.autotag.TagInfoVO;
import com.easywecom.wecom.domain.vo.autotag.TagRuleListVO;
import com.easywecom.wecom.domain.vo.autotag.customer.TagRuleCustomerInfoVO;
import com.easywecom.wecom.domain.vo.autotag.group.TagRuleGroupInfoVO;
import com.easywecom.wecom.domain.vo.autotag.keyword.TagRuleKeywordInfoVO;
import com.easywecom.wecom.mapper.autotag.WeAutoTagRuleMapper;
import com.easywecom.wecom.service.autotag.*;
import com.google.common.collect.Lists;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ???????????????(WeAutoTagRule)??????????????????
 *
 * @author tigger
 * @since 2022-02-27 15:52:40
 */
@Service("weAutoTagRuleService")
public class WeAutoTagRuleServiceImpl extends ServiceImpl<WeAutoTagRuleMapper, WeAutoTagRule> implements WeAutoTagRuleService {

    @Autowired
    private WeAutoTagRuleMapper weAutoTagRuleMapper;
    @Autowired
    private WeAutoTagUserRelService weAutoTagUserRelService;
    @Autowired
    private WeAutoTagKeywordService weAutoTagKeywordService;
    @Autowired
    private WeAutoTagKeywordTagRelService weAutoTagKeywordTagRelService;
    @Autowired
    private WeAutoTagGroupSceneService weAutoTagGroupSceneService;
    @Autowired
    private WeAutoTagGroupSceneGroupRelService weAutoTagGroupSceneGroupRelService;
    @Autowired
    private WeAutoTagGroupSceneTagRelService weAutoTagGroupSceneTagRelService;
    @Autowired
    private WeAutoTagCustomerRuleEffectTimeService weAutoTagCustomerRuleEffectTimeService;
    @Autowired
    private WeAutoTagCustomerSceneService weAutoTagCustomerSceneService;
    @Autowired
    private WeAutoTagCustomerSceneTagRelService weAutoTagCustomerSceneTagRelService;

    /**
     * ?????????????????????
     *
     * @param query
     * @return
     */
    @Override
    public List<TagRuleListVO> listKeyword(TagRuleQuery query) {
        checkCorpId(query.getCorpId());
        return filterTag(weAutoTagRuleMapper.listKeyword(query), query.getTagIdList());
    }


    /**
     * ???????????????
     *
     * @param query
     * @return
     */
    @Override
    public List<TagRuleListVO> listGroup(TagRuleQuery query) {
        return filterTag(weAutoTagRuleMapper.listGroup(query), query.getTagIdList());
    }

    /**
     * ??????????????????
     *
     * @param query
     * @return
     */
    @Override
    public List<TagRuleListVO> listCustomer(TagRuleQuery query) {
        return filterTag(weAutoTagRuleMapper.listCustomer(query), query.getTagIdList());
    }

    /**
     * ?????????????????????
     *
     * @return
     */
    @Override
    public TagRuleKeywordInfoVO keywordInfo(RuleInfoQuery query) {
        checkCorpId(query.getCorpId());
        return weAutoTagRuleMapper.keywordInfo(query);
    }

    /**
     * ???????????????
     *
     * @return
     */
    @Override
    public TagRuleGroupInfoVO groupInfo(RuleInfoQuery query) {
        checkCorpId(query.getCorpId());
        return weAutoTagRuleMapper.groupInfo(query);
    }

    /**
     * ??????????????????
     *
     * @return
     */
    @Override
    public TagRuleCustomerInfoVO customerInfo(RuleInfoQuery query) {
        checkCorpId(query.getCorpId());
        return weAutoTagRuleMapper.customerInfo(query);
    }

    /**
     * ?????????????????????????????????
     *
     * @param addKeywordTagRuleDTO
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public int keywordAdd(AddKeywordTagRuleDTO addKeywordTagRuleDTO) {
        StringUtils.checkCorpId(addKeywordTagRuleDTO.getCorpId());

        // ?????? ??????
        WeAutoTagRule weAutoTagRule = addKeywordTagRuleDTO.toWeAutoTagRule();

        int result = weAutoTagRuleMapper.insert(weAutoTagRule);
        final Long ruleId = weAutoTagRule.getId();
        // ?????? ??????/????????????
        weAutoTagUserRelService.batchSave(addKeywordTagRuleDTO.toWeAutoTagUserRel(ruleId));
        // ???????????????
        weAutoTagKeywordService.batchSave(addKeywordTagRuleDTO.toWeAutoTagKeywordList(ruleId));
        // ????????????
        weAutoTagKeywordTagRelService.batchSave(addKeywordTagRuleDTO.toWeAutoTagKeywordTagRelList(ruleId));

        return result;
    }

    /**
     * ???????????????????????????
     *
     * @param addGroupTagRuleDTO
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public int groupAdd(AddGroupTagRuleDTO addGroupTagRuleDTO) {
        StringUtils.checkCorpId(addGroupTagRuleDTO.getCorpId());
        // ?????? ??????
        WeAutoTagRule weAutoTagRule = addGroupTagRuleDTO.toWeAutoTagRule();
        int result = weAutoTagRuleMapper.insert(weAutoTagRule);
        final Long ruleId = weAutoTagRule.getId();

        // ?????? ?????????
        List<WeAutoTagGroupScene> weAutoTagGroupSceneList = addGroupTagRuleDTO.toWeAutoTagGroupSceneList(ruleId, addGroupTagRuleDTO.getCorpId());
        weAutoTagGroupSceneService.batchSave(weAutoTagGroupSceneList);
        // ?????? ?????????
        List<Long> groupSceneIdList = weAutoTagGroupSceneList.stream().map(WeAutoTagGroupScene::getId).collect(Collectors.toList());
        weAutoTagGroupSceneGroupRelService.batchSave(addGroupTagRuleDTO.toWeAutoTagGroupSceneGroupRelList(groupSceneIdList, ruleId));
        // ?????? ????????????
        weAutoTagGroupSceneTagRelService.batchSave(addGroupTagRuleDTO.toWeAutoTagGroupSceneTagRelList(groupSceneIdList, ruleId));

        return result;
    }

    /**
     * ??????????????????????????????
     *
     * @param addCustomerTagRuleDTO
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public int customerAdd(AddCustomerTagRuleDTO addCustomerTagRuleDTO) {
        StringUtils.checkCorpId(addCustomerTagRuleDTO.getCorpId());
        // ?????? ??????
        WeAutoTagRule weAutoTagRule = addCustomerTagRuleDTO.toWeAutoTagRule();
        int result = weAutoTagRuleMapper.insert(weAutoTagRule);
        final Long ruleId = weAutoTagRule.getId();

        // ????????????????????????
        WeAutoTagCustomerRuleEffectTime weAutoTagCustomerRuleEffectTime = addCustomerTagRuleDTO.toWeAutoTagCustomerRuleEffectTime(ruleId);
        if (weAutoTagCustomerRuleEffectTime != null) {
            weAutoTagCustomerRuleEffectTimeService.save(weAutoTagCustomerRuleEffectTime);
        }
        // ?????? ????????????
        weAutoTagUserRelService.batchSave(addCustomerTagRuleDTO.toWeAutoTagUserRel(ruleId));

        // ?????? ????????????
        List<WeAutoTagCustomerScene> weAutoTagCustomerSceneList = addCustomerTagRuleDTO.toWeAutoTagCustomerSceneList(ruleId, addCustomerTagRuleDTO.getCorpId());
        weAutoTagCustomerSceneService.batchSave(weAutoTagCustomerSceneList);

        // ??????????????????
        List<Long> customerSceneIdList = weAutoTagCustomerSceneList.stream().map(WeAutoTagCustomerScene::getId).collect(Collectors.toList());
        weAutoTagCustomerSceneTagRelService.batchSave(addCustomerTagRuleDTO.toWeAutoTagCustomerSceneTagRelList(customerSceneIdList, ruleId));

        return result;
    }

    /**
     * ?????????????????????????????????
     *
     * @param updateKeywordTagRuleDTO
     * @param corpId
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public int keywordEdit(UpdateKeywordTagRuleDTO updateKeywordTagRuleDTO, String corpId) {
        checkCorpId(corpId);
        // ????????????
        WeAutoTagRule weAutoTagRule = updateKeywordTagRuleDTO.toWeAutoTagRule();
        int result = weAutoTagRuleMapper.updateById(weAutoTagRule);
        Long ruleId = weAutoTagRule.getId();
        // ????????????????????????
        weAutoTagUserRelService.edit(updateKeywordTagRuleDTO.toWeAutoTagUserRel(ruleId), ruleId);
        // ???????????????
        weAutoTagKeywordService.edit(updateKeywordTagRuleDTO.getReomveFuzzyMatchKeywordList(),
                updateKeywordTagRuleDTO.getRemoveExactMatchKeywordList(), updateKeywordTagRuleDTO.toWeAutoTagKeywordList(), ruleId);
        // ?????????????????????
        weAutoTagKeywordTagRelService.edit(updateKeywordTagRuleDTO.getRemoveTagIdList(),
                updateKeywordTagRuleDTO.toWeAutoTagKeywordTagRelList(), ruleId);
        return result;
    }

    /**
     * ???????????????????????????
     *
     * @param updateGroupTagRuleDTO
     * @param corpId
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public int groupEdit(UpdateGroupTagRuleDTO updateGroupTagRuleDTO, String corpId) {
        checkCorpId(corpId);
        WeAutoTagRule weAutoTagRule = updateGroupTagRuleDTO.toWeAutoTagRule();
        int result = weAutoTagRuleMapper.updateById(weAutoTagRule);
        Long ruleId = weAutoTagRule.getId();

        // ?????? ?????????
        List<WeAutoTagGroupScene> weAutoTagGroupSceneList = updateGroupTagRuleDTO.toWeAutoTagGroupSceneList(ruleId, corpId);
        weAutoTagGroupSceneService.edit(updateGroupTagRuleDTO.getRemoveSceneIdList(), weAutoTagGroupSceneList);
        // ?????? ??????????????????
        List<Long> groupSceneIdList = weAutoTagGroupSceneList.stream().map(WeAutoTagGroupScene::getId).collect(Collectors.toList());
        weAutoTagGroupSceneGroupRelService.edit(updateGroupTagRuleDTO.getGroupSceneList(), updateGroupTagRuleDTO.toWeAutoTagGroupSceneGroupRelList(groupSceneIdList, ruleId));
        // ?????? ?????????????????????
        weAutoTagGroupSceneTagRelService.edit(updateGroupTagRuleDTO.getGroupSceneList(), updateGroupTagRuleDTO.toWeAutoTagGroupSceneTagRelList(groupSceneIdList, ruleId));

        return result;
    }

    /**
     * ??????????????????????????????
     *
     * @param updateCustomerTagRuleDTO
     * @param corpId
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public int customerEdit(UpdateCustomerTagRuleDTO updateCustomerTagRuleDTO, String corpId) {
        checkCorpId(corpId);
        WeAutoTagRule weAutoTagRule = updateCustomerTagRuleDTO.toWeAutoTagRule();
        int result = weAutoTagRuleMapper.updateById(weAutoTagRule);
        Long ruleId = weAutoTagRule.getId();

        // ?????? ????????????
        weAutoTagUserRelService.edit(updateCustomerTagRuleDTO.toWeAutoTagUserRel(ruleId), ruleId);
        // ?????? ????????????
        WeAutoTagCustomerRuleEffectTime weAutoTagCustomerRuleEffectTime = updateCustomerTagRuleDTO.toWeAutoTagCustomerRuleEffectTime(ruleId);
        // ?????????null->??????
        if (weAutoTagCustomerRuleEffectTime != null) {
            weAutoTagCustomerRuleEffectTimeService.edit(weAutoTagCustomerRuleEffectTime);
        }else{
            // ??????null -> ??????
            weAutoTagCustomerRuleEffectTimeService.removeByRuleIdList(Collections.singletonList(ruleId));
        }
        // ?????? ????????????
        List<WeAutoTagCustomerScene> weAutoTagCustomerSceneList = updateCustomerTagRuleDTO.toWeAutoTagCustomerSceneList(ruleId, corpId);
        weAutoTagCustomerSceneService.edit(updateCustomerTagRuleDTO.getRemoveSceneIdList(), weAutoTagCustomerSceneList);

        // ?????? ??????????????????
        List<Long> customerSceneIdList = weAutoTagCustomerSceneList.stream().map(WeAutoTagCustomerScene::getId).collect(Collectors.toList());
        weAutoTagCustomerSceneTagRelService.edit(updateCustomerTagRuleDTO.getCustomerSceneList(), updateCustomerTagRuleDTO.toWeAutoTagCustomerSceneTagRelList(customerSceneIdList, ruleId));

        return result;
    }

    /**
     * ???????????????????????????
     *
     * @param deleteDTO
     * @param corpId
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public int keywordDelete(TagRuleDeleteDTO deleteDTO, String corpId) {
        checkDeleteParam(deleteDTO, corpId);
        List<Long> removeRuleIdList = deleteDTO.getIdList();
        // ????????????
        int result = this.baseMapper.deleteBatchIds(removeRuleIdList);

        // ????????????????????????
        weAutoTagUserRelService.removeByRuleIdList(removeRuleIdList);

        // ???????????????
        weAutoTagKeywordService.removeByRuleIdList(removeRuleIdList);
        // ????????????
        weAutoTagKeywordTagRelService.removeByRuleIdList(removeRuleIdList);

        return result;
    }


    /**
     * ?????????????????????
     *
     * @param deleteDTO
     * @param corpId
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public int groupDelete(TagRuleDeleteDTO deleteDTO, String corpId) {
        checkDeleteParam(deleteDTO, corpId);
        List<Long> removeRuleIdList = deleteDTO.getIdList();
        // ????????????
        int result = this.baseMapper.deleteBatchIds(removeRuleIdList);
        // ????????????
        weAutoTagGroupSceneService.removeByRuleIdList(removeRuleIdList);
        // ???????????????
        weAutoTagGroupSceneGroupRelService.removeByRuleIdList(removeRuleIdList);
        // ??????????????????
        weAutoTagGroupSceneTagRelService.removeRuleIdList(removeRuleIdList);
        return result;
    }

    /**
     * ????????????????????????
     *
     * @param deleteDTO
     * @param corpId
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public int customerDelete(TagRuleDeleteDTO deleteDTO, String corpId) {
        checkDeleteParam(deleteDTO, corpId);
        List<Long> removeRuleIdList = deleteDTO.getIdList();
        // ????????????
        int result = this.baseMapper.deleteBatchIds(removeRuleIdList);
        // ????????????????????????
        weAutoTagUserRelService.removeByRuleIdList(removeRuleIdList);
        // ??????????????????
        weAutoTagCustomerRuleEffectTimeService.removeByRuleIdList(removeRuleIdList);
        // ????????????
        weAutoTagCustomerSceneService.removeByRuleIdList(removeRuleIdList);
        // ????????????
        weAutoTagCustomerSceneTagRelService.removeByRuleIdList(removeRuleIdList);
        return result;
    }

    /**
     * ??????????????????
     *
     * @param tagRuleBatchStatusDTO
     * @return
     */
    @Override
    public Boolean batchStatus(TagRuleBatchStatusDTO tagRuleBatchStatusDTO) {
        StringUtils.checkCorpId(tagRuleBatchStatusDTO.getCorpId());
        if (tagRuleBatchStatusDTO == null || tagRuleBatchStatusDTO.getStatus() == null
                || CollectionUtils.isEmpty(tagRuleBatchStatusDTO.getIdList())) {
            log.error("????????????");
            throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
        }
        return this.update(new LambdaUpdateWrapper<WeAutoTagRule>()
                .set(WeAutoTagRule::getStatus, tagRuleBatchStatusDTO.getStatus())
                .in(WeAutoTagRule::getId, tagRuleBatchStatusDTO.getIdList()));
    }

    /**
     * ?????????????????????????????????
     *
     * @param corpId
     * @return
     */
    @Override
    public List<Long> getCandidateCustomerRuleIdList(String corpId) {
        StringUtils.checkCorpId(corpId);
        List<Long> candidatesRuleIdList = new ArrayList<>();
        // ????????????????????????????????????
        List<Long> normalRuleIdList = this.selectEnableRuleIdByLabelType(AutoTagLabelTypeEnum.CUSTOMER.getType(), corpId);
        if (CollectionUtils.isEmpty(normalRuleIdList)) {
            return candidatesRuleIdList;
        }

        // ????????????????????????????????????????????????,?????????????????????id??????
        List<Long> notHadEffectTimeRuleIdList = new ArrayList<>(normalRuleIdList);
        List<Long> hasEffectTimeRuleIdList = weAutoTagCustomerRuleEffectTimeService.selectHadEffectTimeRule(normalRuleIdList, corpId);
        if (CollectionUtils.isNotEmpty(hasEffectTimeRuleIdList)) {
            // ?????????????????????????????????id??????
            notHadEffectTimeRuleIdList.removeAll(hasEffectTimeRuleIdList);
        }
        candidatesRuleIdList.addAll(notHadEffectTimeRuleIdList);

        // ???hasEffectTimeRuleIdList????????????????????????id,??????????????????id??????
        if (CollectionUtils.isNotEmpty(hasEffectTimeRuleIdList)) {
            String nowStr = DateUtils.parseDateToStr(DateUtils.YYYY_MM_DD_HH_MM_SS, new Date());
            List<WeAutoTagCustomerRuleEffectTime> list = weAutoTagCustomerRuleEffectTimeService.list(new LambdaQueryWrapper<WeAutoTagCustomerRuleEffectTime>()
                    .le(WeAutoTagCustomerRuleEffectTime::getEffectBeginTime, nowStr)
                    .ge(WeAutoTagCustomerRuleEffectTime::getEffectEndTime, nowStr)
                    .in(WeAutoTagCustomerRuleEffectTime::getRuleId, hasEffectTimeRuleIdList)
                    .select(WeAutoTagCustomerRuleEffectTime::getRuleId));
            // ??????????????????????????????id??????
            candidatesRuleIdList.addAll(list.stream().map(WeAutoTagCustomerRuleEffectTime::getRuleId).collect(Collectors.toList()));
        }

        return candidatesRuleIdList;
    }


    /**
     * ???????????????????????????????????????id??????
     *
     * @param corpId
     * @param labelType
     * @return
     */
    @Override
    public List<Long> listContainUserScopeRuleIdList(String corpId, Integer labelType) {
        if (labelType.equals(AutoTagLabelTypeEnum.GROUP.getType()) || StringUtils.isBlank(corpId)) {
            return Lists.newArrayList();
        }
        return this.baseMapper.selectContainUserScopeRuleIdList(corpId, labelType);
    }

    /**
     * ??????????????????????????????
     *
     * @param labelType ????????????
     * @param corpId    ??????id
     * @return
     */
    @Override
    public List<Long> selectEnableRuleIdByLabelType(Integer labelType, String corpId) {
        StringUtils.checkCorpId(corpId);
        if (!AutoTagLabelTypeEnum.existType(labelType)) {
            log.error("labelType error:  {}");
            throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
        }
        return this.baseMapper.selectList(new LambdaQueryWrapper<WeAutoTagRule>()
                .eq(WeAutoTagRule::getLabelType, labelType)
                .eq(WeAutoTagRule::getStatus, 1)
                .eq(WeAutoTagRule::getCorpId, corpId))
                .stream()
                .map(WeAutoTagRule::getId).collect(Collectors.toList());
    }

    /**
     * ????????????????????????????????????
     *
     * @param labelType ????????????
     * @param corpId    ??????id
     * @return
     */
    @Override
    public List<Long> listRuleIdByLabelType(Integer labelType, String corpId) {
        if (!AutoTagLabelTypeEnum.existType(labelType) || StringUtils.isBlank(corpId)) {
            return Lists.newArrayList();
        }
        return this.baseMapper.selectRuleIdByLabelType(labelType, corpId);
    }

    /**
     * ????????????????????????
     *
     * @param deleteDTO
     * @param corpId
     */
    private void checkDeleteParam(TagRuleDeleteDTO deleteDTO, String corpId) {
        checkCorpId(corpId);
        if (deleteDTO == null || CollectionUtils.isEmpty(deleteDTO.getIdList())) {
            throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
        }
    }

    /**
     * ????????????id
     *
     * @param corpId ??????id
     */
    private void checkCorpId(String corpId) {
        StringUtils.checkCorpId(corpId);
    }

    /**
     * ????????????
     *
     * @param list      ??????????????????
     * @param tagIdList ???????????????idlist
     */
    private List<TagRuleListVO> filterTag(List<TagRuleListVO> list, List<String> tagIdList) {
        if (CollectionUtils.isNotEmpty(tagIdList)) {
            return list.stream().filter(item -> {
                List<String> originTagIdList = item.getTagList().stream().map(TagInfoVO::getTagId).collect(Collectors.toList());
                return !Collections.disjoint(originTagIdList, tagIdList);
            }).collect(Collectors.toList());
        }
        return list;
    }

}

