package com.easywecom.wecom.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.easywecom.common.config.RuoYiConfig;
import com.easywecom.common.constant.GroupCodeConstants;
import com.easywecom.common.constant.WeConstans;
import com.easywecom.common.core.domain.AjaxResult;
import com.easywecom.common.core.domain.entity.WeCorpAccount;
import com.easywecom.common.enums.ResultTip;
import com.easywecom.common.enums.code.GroupCodeTypeEnum;
import com.easywecom.common.enums.wecom.ServerTypeEnum;
import com.easywecom.common.exception.CustomException;
import com.easywecom.common.exception.wecom.WeComException;
import com.easywecom.common.utils.DateUtils;
import com.easywecom.common.utils.QREncode;
import com.easywecom.common.utils.StringUtils;
import com.easywecom.common.utils.file.FileUploadUtils;
import com.easywecom.wecom.domain.WeGroup;
import com.easywecom.wecom.domain.WeGroupCode;
import com.easywecom.wecom.domain.WeGroupCodeActual;
import com.easywecom.wecom.domain.dto.FindWeGroupCodeDTO;
import com.easywecom.wecom.domain.query.groupcode.GroupCodeDetailQuery;
import com.easywecom.wecom.domain.vo.WeGroupCodeActualExistVO;
import com.easywecom.wecom.domain.vo.groupcode.GroupCodeActivityFirstVO;
import com.easywecom.wecom.domain.vo.groupcode.GroupCodeDetailVO;
import com.easywecom.wecom.login.util.LoginTokenService;
import com.easywecom.wecom.mapper.WeGroupCodeActualMapper;
import com.easywecom.wecom.mapper.WeGroupCodeMapper;
import com.easywecom.wecom.mapper.WeGroupMapper;
import com.easywecom.wecom.service.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ???????????????Service???????????????
 *
 * @author admin
 * @date 2020-10-07
 */
@Service
@Slf4j
public class WeGroupCodeServiceImpl extends ServiceImpl<WeGroupCodeMapper, WeGroupCode> implements WeGroupCodeService {
    private final WeGroupCodeActualService weGroupCodeActualService;
    private final WeGroupCodeActualMapper actualCodeMapper;
    private final WeCorpAccountService weCorpAccountService;
    private final RuoYiConfig ruoYiConfig;
    private final WeGroupCodeMapper weGroupCodeMapper;
    private final We3rdAppService we3rdAppService;
    private final WeGroupMapper weGroupMapper;


    @Lazy
    @Autowired
    public WeGroupCodeServiceImpl(WeGroupCodeActualService weGroupCodeActualService, WeGroupCodeActualMapper actualCodeMapper, WeCorpAccountService weCorpAccountService, RuoYiConfig ruoYiConfig, WeGroupCodeMapper weGroupCodeMapper, We3rdAppService we3rdAppService, WeGroupMapper weGroupMapper) {
        this.weGroupCodeActualService = weGroupCodeActualService;
        this.actualCodeMapper = actualCodeMapper;
        this.weCorpAccountService = weCorpAccountService;
        this.ruoYiConfig = ruoYiConfig;
        this.weGroupCodeMapper = weGroupCodeMapper;
        this.we3rdAppService = we3rdAppService;
        this.weGroupMapper = weGroupMapper;
    }

    /**
     * ?????????????????????
     *
     * @param weGroupCode ???????????????
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(WeGroupCode weGroupCode) {
        if (weGroupCode == null) {
            throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
        }
        // ??????????????????????????????
        GroupCodeTypeEnum.assertNotNull(weGroupCode.getCreateType());

        // ????????????????????????
        if (GroupCodeTypeEnum.GROUP_QR.getType().equals(weGroupCode.getCreateType())) {
            groupCodeAddHandle(weGroupCode);
        }
        if (GroupCodeTypeEnum.CORP_QR.getType().equals(weGroupCode.getCreateType())) {
            corpCodeAddHandle(weGroupCode);
        }
        // ?????????????????????????????????????????????????????????URL
        buildQrUrl(weGroupCode);
        //???????????????
        insertWeGroupCode(weGroupCode);
    }

    /**
     * ??????????????????????????????
     *
     * @param weGroupCode
     */
    private void groupCodeAddHandle(WeGroupCode weGroupCode) {
        checkGroupParams(weGroupCode);
        List<WeGroupCodeActual> actualList = weGroupCode.getActualList();
        //??????????????????
        if (CollectionUtils.isNotEmpty(actualList)) {
            buildActualList(actualList, weGroupCode.getId());
            weGroupCodeActualService.saveBatch(actualList);
            String actualIds = actualList.stream()
                    .map(weGroupCodeActual -> weGroupCodeActual.getId().toString()).collect(Collectors.joining(StrUtil.COMMA));
            weGroupCode.setSeq(actualIds);
        }
    }

    /**
     * ?????????????????????????????????
     *
     * @param weGroupCode ????????????
     */
    private void checkGroupParams(WeGroupCode weGroupCode) {
        checkBase(weGroupCode);
        if (CollectionUtils.isNotEmpty(weGroupCode.getActualList())) {
            int preSize = weGroupCode.getActualList().size();
            int postSize = weGroupCode.getActualList().stream()
                    .map(WeGroupCodeActual::getChatId).collect(Collectors.toSet()).size();
            if (preSize != postSize) {
                log.error("com.easywecom.wecom.service.impl.WeGroupCodeServiceImpl.checkGroupParams: ????????????????????????,?????????????????????????????????");
                throw new CustomException(ResultTip.TIP_ACTUAL_GROUP_CODE_EXIST);
            }
            for (WeGroupCodeActual codeActual : weGroupCode.getActualList()) {
                if (StringUtils.isEmpty(codeActual.getChatId())) {
                    log.error("com.easywecom.wecom.service.impl.WeGroupCodeServiceImpl.checkGroupParams: ??????????????????????????????");
                    throw new CustomException(ResultTip.TIP_MISS_ACTUAL_GROUP);
                }
            }
        }
        //??????????????????
        checkActualNum(weGroupCode.getActualList(), GroupCodeConstants.ACTUAL_GROUP_NUM_LIMIT, GroupCodeConstants.DEFAULT_GROUP_NUM,
                ResultTip.TIP_ACTUAL_GROUP_OVER_NUM_TWO_HUNDRED);
    }

    /**
     * ??????????????????????????????
     *
     * @param weGroupCode
     */
    private void corpCodeAddHandle(WeGroupCode weGroupCode) {
        // ????????????????????????
        String serverType = we3rdAppService.getServerType().getServerType();

        checkCorpParams(weGroupCode, serverType);

        Optional.ofNullable(weGroupCode.getActualList())
                .ifPresent(list -> {
                    if (ServerTypeEnum.THIRD.getType().equals(serverType)) {
                        weGroupCodeActualService.addThirdWeGroupCodeCorpActualBatch(list, weGroupCode.getId());
                    }
                    if (ServerTypeEnum.INTERNAL.getType().equals(serverType)) {
                        // ???????????????????????????????????????
                        weGroupCodeActualService.addInnerWeGroupCodeCorpActualBatch(list, weGroupCode.getId(), Boolean.FALSE, weGroupCode.getCorpId());
                    }
                    // ??????????????????
                    String actualIds = weGroupCode.getActualList().stream()
                            .map(weGroupCodeActual -> weGroupCodeActual.getId().toString()).collect(Collectors.joining(StrUtil.COMMA));
                    weGroupCode.setSeq(actualIds);
                });

    }

    /**
     * ???????????????????????????????????????
     *
     * @param weGroupCode ????????????
     * @param serverType  ????????????
     */
    private void checkCorpParams(WeGroupCode weGroupCode, String serverType) {
        checkBase(weGroupCode);
        // ?????????????????????????????????????????????
        if (CollectionUtils.isNotEmpty(weGroupCode.getActualList())) {
            for (WeGroupCodeActual corpActual : weGroupCode.getActualList()) {
                if (ServerTypeEnum.INTERNAL.getType().equals(serverType)) {
                    // ???????????????
                    if (StringUtils.isEmpty(corpActual.getChatIds())) {
                        log.error("com.easywecom.wecom.service.impl.WeGroupCodeServiceImpl.checkCorpParams: ???????????????");
                        throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
                    }
                    // ??????????????????????????????????????????????????????5
                    if (corpActual.getChatIds().split(StrUtil.COMMA).length > GroupCodeConstants.CORP_ACTUAL_CODE_REF_GROUP_LIMIT) {
                        throw new CustomException(ResultTip.TIP_ACTUAL_GROUP_REF_GROUP_LIMIT_SIZE);
                    }
                }
                // ???????????????
                if (StringUtils.isEmpty(corpActual.getActualGroupQrCode())) {
                    log.error("com.easywecom.wecom.service.impl.WeGroupCodeServiceImpl.checkCorpParams: ????????????????????????");
                    throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
                }
            }
            // ????????????
            checkActualNum(weGroupCode.getActualList(), GroupCodeConstants.CORP_ACTUAL_GROUP_NUM_LIMIT,
                    GroupCodeConstants.DEFAULT_CORP_GROUP_NUM, ResultTip.TIP_ACTUAL_GROUP_OVER_NUM_ONE_THOUSAND);
        }
    }

    /**
     * ???????????????id?????????????????????
     *
     * @param groupCodeId ?????????id
     * @return ??????
     */
    @Override
    public List<WeGroupCodeActual> selectActualList(Long groupCodeId) {
        return actualCodeMapper.selectActualList(groupCodeId);
    }

    /**
     * ???????????????????????????
     *
     * @param weGroupCode ???????????????
     * @return ???????????????
     */
    @Override
    public List<WeGroupCode> selectWeGroupCodeList(FindWeGroupCodeDTO weGroupCode) {
        if (weGroupCode == null || StringUtils.isEmpty(weGroupCode.getCorpId())) {
            log.error("com.easywecom.wecom.service.impl.WeGroupCodeServiceImpl.selectWeGroupCodeList: corpId????????????");
            throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
        }
        if (org.apache.commons.lang3.StringUtils.isNotBlank(weGroupCode.getBeginTime())) {
            weGroupCode.setBeginTime(DateUtils.parseBeginDay(DateUtils.timeFormatTrans(weGroupCode.getBeginTime(), DateUtils.YYYYMMDD, DateUtils.YYYY_MM_DD)));
        }
        if (org.apache.commons.lang3.StringUtils.isNotBlank(weGroupCode.getEndTime())) {
            weGroupCode.setEndTime(DateUtils.parseEndDay(DateUtils.timeFormatTrans(weGroupCode.getEndTime(), DateUtils.YYYYMMDD, DateUtils.YYYY_MM_DD)));
        }
        List<WeGroupCode> weGroupCodeList = baseMapper.selectWeGroupCodeList(weGroupCode);
        // ?????????????????????????????????????????????????????????????????????
        for (WeGroupCode item : weGroupCodeList) {
            List<WeGroupCodeActual> actualList = actualCodeMapper.selectActualList(item.getId());
            item.setActualList(actualList);
        }
        return weGroupCodeList;
    }

    @Override
    public List<WeGroupCode> selectExpireCode(String corpId) {
        StringUtils.checkCorpId(corpId);
        return weGroupCodeMapper.listOfExpireGroupCode(corpId);
    }

    /**
     * ?????????????????????
     *
     * @param weGroupCode ???????????????
     */
    private void insertWeGroupCode(WeGroupCode weGroupCode) {
        if (weGroupCode == null || StringUtils.isBlank(weGroupCode.getCorpId())) {
            throw new CustomException(ResultTip.TIP_MISS_CORP_ID);
        }
        baseMapper.insertWeGroupCode(weGroupCode);
    }

    /**
     * ?????????????????????
     *
     * @param weGroupCode ???????????????
     * @return ??????
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateWeGroupCode(WeGroupCode weGroupCode) {
        if (weGroupCode == null || StringUtils.isBlank(weGroupCode.getCorpId())) {
            throw new CustomException(ResultTip.TIP_MISS_CORP_ID);
        }
        return baseMapper.updateWeGroupCode(weGroupCode);
    }

    /**
     * ????????????????????????
     *
     * @param actualList  ????????????
     * @param groupCodeId ?????????id
     */
    private void buildActualList(List<WeGroupCodeActual> actualList, Long groupCodeId) {
        for (WeGroupCodeActual weGroupCodeActual : actualList) {
            weGroupCodeActual.setGroupCodeId(groupCodeId);
            if (weGroupCodeActual.getEffectTime() == null) {
                weGroupCodeActual.setEffectTime(DateUtils.dateTime(DateUtils.YYYY_MM_DD_HH_MM, WeConstans.DEFAULT_MATERIAL_NOT_EXPIRE));
            }
        }
        //???????????????????????????????????????
        WeGroupCodeActualExistVO weGroupCodeActualExistVO = weGroupCodeActualService.checkChatIdUnique(actualList, groupCodeId);
        if (weGroupCodeActualExistVO.getCount() > 0) {
            throw new CustomException(ResultTip.TIP_ACTUAL_GROUP_CODE_EXIST);
        }
    }

    /**
     * ???????????????????????????
     *
     * @param ids ??????????????????????????????ID
     * @return ??????
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int remove(Long[] ids) {

        // ???????????????
        List<WeGroupCode> weGroupCodeList = this.baseMapper.selectBatchIds(Arrays.asList(ids));
        // ?????????????????????????????????????????????
        Map<Boolean, List<WeGroupCode>> groupCodeTypeMap = weGroupCodeList.stream()
                .collect(Collectors.partitioningBy(item -> GroupCodeTypeEnum.GROUP_QR.getType().equals(item.getCreateType())));
        // true,??????????????????
        if (CollectionUtils.isNotEmpty(groupCodeTypeMap.get(Boolean.TRUE))) {
            groupCodeDeleteHandle(groupCodeTypeMap.get(Boolean.TRUE));
        }
        // false, ????????????????????????
        if (CollectionUtils.isNotEmpty(groupCodeTypeMap.get(Boolean.TRUE))) {
            corpCodeDeleteHandle(groupCodeTypeMap.get(Boolean.FALSE), LoginTokenService.getLoginUser().getCorpId());
        }
        // ????????????
        return removeByIds(Arrays.asList(ids)) ? 1 : 0;
    }

    /**
     * ????????????????????????
     *
     * @param weGroupCodeList
     */
    private void groupCodeDeleteHandle(List<WeGroupCode> weGroupCodeList) {
        if (CollectionUtils.isNotEmpty(weGroupCodeList)) {
            LambdaQueryWrapper<WeGroupCodeActual> wrapper = new LambdaQueryWrapper<>();
            wrapper.in(WeGroupCodeActual::getGroupCodeId, weGroupCodeList.stream().map(WeGroupCode::getId).collect(Collectors.toList()));
            actualCodeMapper.delete(wrapper);
        }
    }

    /**
     * ??????????????????????????????
     *
     * @param weGroupCodeList
     * @param corpId
     */
    private void corpCodeDeleteHandle(List<WeGroupCode> weGroupCodeList, String corpId) {
        // ??????????????????????????????
        if (CollectionUtils.isNotEmpty(weGroupCodeList)) {
            // ????????????????????????
            String serverType = we3rdAppService.getServerType().getServerType();
            List<Long> removeIds = weGroupCodeList.stream().map(WeGroupCode::getId).collect(Collectors.toList());
            if (ServerTypeEnum.THIRD.getType().equals(serverType)) {
                weGroupCodeActualService.removeThirdWeGroupCodeActualByIds(removeIds);
            }
            if (ServerTypeEnum.INTERNAL.getType().equals(serverType)) {
                weGroupCodeActualService.removeInnerWeGroupCodeActualByIds(removeIds, corpId);
            }
        }
    }


    /**
     * ?????????????????????????????????
     *
     * @param weGroupCode ????????????
     * @return ??????
     */
    @Override
    public boolean isNameOccupied(WeGroupCode weGroupCode) {
        StringUtils.checkCorpId(weGroupCode.getCorpId());
        Long currentId = Optional.ofNullable(weGroupCode.getId()).orElse(-1L);
        LambdaQueryWrapper<WeGroupCode> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WeGroupCode::getActivityName, weGroupCode.getActivityName()).eq(WeGroupCode::getDelFlag, WeConstans.WE_CUSTOMER_MSG_RESULT_NO_DEFALE).eq(WeGroupCode::getCorpId, weGroupCode.getCorpId());
        List<WeGroupCode> res = baseMapper.selectList(queryWrapper);
        return !res.isEmpty() && !currentId.equals(res.get(0).getId());
    }

    /**
     * ???????????????????????????????????????????????????????????????
     *
     * @param state  ????????????state
     * @param corpId ??????Id
     * @return ?????????URL
     */
    @Override
    public String selectGroupCodeUrlByEmplCodeState(String state, String corpId) {
        StringUtils.checkCorpId(corpId);
        return baseMapper.selectGroupCodeUrlByEmplCodeState(state, corpId);
    }

    /**
     * ????????????????????????,???????????????
     *
     * @param actualList ???????????????
     * @param limitNum   ?????????
     * @param defaultNum ?????????
     * @param resultTip  ??????????????????
     */
    private void checkActualNum(List<WeGroupCodeActual> actualList, Integer limitNum, Integer defaultNum, ResultTip resultTip) {
        actualList.forEach(weGroupCodeActual -> {
            if (weGroupCodeActual.getScanCodeTimesLimit() == null) {
                weGroupCodeActual.setScanCodeTimesLimit(defaultNum);
            }
            if (weGroupCodeActual.getScanCodeTimesLimit() > limitNum) {
                throw new CustomException(resultTip);
            }
        });
    }


    /**
     * ?????????????????????
     *
     * @param weGroupCode
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public int edit(WeGroupCode weGroupCode) {
        // ????????????????????????
        if (GroupCodeTypeEnum.GROUP_QR.getType().equals(weGroupCode.getCreateType())) {
            groupCodeEditHandle(weGroupCode);
        }
        if (GroupCodeTypeEnum.CORP_QR.getType().equals(weGroupCode.getCreateType())) {
            corpCodeEditHandle(weGroupCode);
        }
        // ?????????????????????????????????????????????????????????URL
        buildQrUrl(weGroupCode);
        // ?????????????????????
        return updateWeGroupCode(weGroupCode);
    }

    /**
     * ???????????????????????? -> ??????????????????????????????
     *
     * @param groupCodeDetailQuery
     * @param createType
     * @return
     */
    @Override
    public List<GroupCodeDetailVO> getGroupCodeDetail(GroupCodeDetailQuery groupCodeDetailQuery, Integer createType) {
        List<GroupCodeDetailVO> resulList;
        resulList = actualCodeMapper.selectGroupActualListWithGroupQr(groupCodeDetailQuery);
        if (GroupCodeTypeEnum.CORP_QR.getType().equals(createType)) {
            // ???????????????
            for (GroupCodeDetailVO detailVO : resulList) {
                String[] split = detailVO.getChatIds().split(StrUtil.COMMA);
                List<String> chatIds = Arrays.asList(split);
                List<WeGroup> weGroups = weGroupMapper.selectBatchIds(chatIds);
                detailVO.setGroupDetailVOList(weGroups);
            }
        }
        return resulList;
    }

    /**
     * ????????????????????????????????????
     *
     * @param id        ?????????id
     * @param groupCode
     * @return
     */
    @Override
    public GroupCodeActivityFirstVO doGetActual(Long id, WeGroupCode groupCode) {

        List<WeGroupCodeActual> actualCodeList = this.selectActualList(id);
        WeGroupCodeActual groupCodeActual = null;
        for (WeGroupCodeActual item : actualCodeList) {
            // ?????????????????????????????????
            if (WeConstans.WE_GROUP_CODE_ENABLE.equals(item.getStatus()) &&
                    item.getScanCodeTimesLimit() > item.getScanCodeTimes()) {
                groupCodeActual = item;
                break;
            } else {
                // ??????????????????
                updateStatusDisableIfNecessory(item.getId());
            }
        }
        GroupCodeActivityFirstVO activityFirstVO = null;
        if (groupCodeActual != null) {
            // ??????????????????????????????????????????????????????+1
            addScanCodeTimesIfNecessory(groupCode.getCreateType(), groupCodeActual.getId());

            activityFirstVO = new GroupCodeActivityFirstVO();
            activityFirstVO.setActivityName(groupCode.getActivityName());
            activityFirstVO.setTipMsg(groupCode.getTipMsg());
            activityFirstVO.setGuide(groupCode.getGuide());
            activityFirstVO.setActualQRCode(groupCodeActual.getActualGroupQrCode());
            activityFirstVO.setIsOpenTip(groupCode.getShowTip().toString());
            activityFirstVO.setServiceQrCode(groupCode.getCustomerServerQrCode());
            activityFirstVO.setGroupName(groupCodeActual.getChatGroupName());
            return activityFirstVO;
        }
        return activityFirstVO;
    }

    /**
     * ???????????????????????????
     *
     * @param id
     */
    private void updateStatusDisableIfNecessory(Long id) {
        actualCodeMapper.updateStatus(id, WeConstans.WE_GROUP_CODE_DISABLE);
    }

    /**
     * ???????????????????????????????????????????????????
     *
     * @param createType ??????????????????
     * @param id         ?????????id
     */
    private void addScanCodeTimesIfNecessory(Integer createType, Long id) {
        if (GroupCodeTypeEnum.CORP_QR.getType().equals(createType)) {
            actualCodeMapper.addScanCodeTimes(id);
        }
    }

    /**
     * ??????????????????????????????
     *
     * @param weGroupCode
     */
    private void groupCodeEditHandle(WeGroupCode weGroupCode) {
        checkGroupParams(weGroupCode);
        // ???????????????
        List<WeGroupCodeActual> actualList = weGroupCode.getActualList();
        if (CollUtil.isNotEmpty(weGroupCode.getDelActualIdList())) {
            weGroupCodeActualService.removeByIds(weGroupCode.getDelActualIdList());
        }
        if (CollUtil.isNotEmpty(actualList)) {
            buildActualList(actualList, weGroupCode.getId());
            weGroupCodeActualService.saveOrUpdateBatch(actualList);
            String actualIds = actualList.stream().map(weGroupCodeActual -> weGroupCodeActual.getId().toString()).collect(Collectors.joining(StrUtil.COMMA));
            weGroupCode.setSeq(actualIds);
        }
    }

    /**
     * ??????????????????????????????
     *
     * @param weGroupCode
     */
    private void corpCodeEditHandle(WeGroupCode weGroupCode) {
        // ????????????????????????
        String serverType = we3rdAppService.getServerType().getServerType();
        checkCorpParams(weGroupCode, serverType);

        Optional.ofNullable(weGroupCode.getActualList())
                .ifPresent(list -> {
                    // ????????????????????????id
                    list.forEach(item -> item.setGroupCodeId(weGroupCode.getId()));
                    // ??????
                    if (ServerTypeEnum.THIRD.getType().equals(serverType)) {
                        weGroupCodeActualService.editThirdWeGroupCodeCorpActualBatch(list);
                        // ???????????????????????????
                        if (CollectionUtils.isNotEmpty(weGroupCode.getDelActualIdList())) {
                            weGroupCodeActualService.removeThirdWeGroupCodeActualByIds(weGroupCode.getDelActualIdList());
                        }
                    }
                    if (ServerTypeEnum.INTERNAL.getType().equals(serverType)) {
                        // ????????????????????????,???????????????????????????
                        weGroupCodeActualService.editInnerWeGroupCodeCorpActualBatch(list, weGroupCode.getId(), Boolean.FALSE, weGroupCode.getCorpId());
                        // ???????????????????????????
                        if (CollectionUtils.isNotEmpty(weGroupCode.getDelActualIdList())) {
                            weGroupCodeActualService.removeInnerWeGroupCodeActualByIds(weGroupCode.getDelActualIdList(), weGroupCode.getCorpId());
                        }
                    }
                    // ??????????????????
                    String actualIds = weGroupCode.getActualList().stream()
                            .map(weGroupCodeActual -> weGroupCodeActual.getId().toString()).collect(Collectors.joining(StrUtil.COMMA));
                    weGroupCode.setSeq(actualIds);
                });
    }

    /**
     * ?????????????????????????????????
     *
     * @param weGroupCode
     */
    private void checkBase(WeGroupCode weGroupCode) {
        if (weGroupCode == null) {
            throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
        }
        if (StringUtils.isEmpty(weGroupCode.getActivityName()) || StringUtils.isEmpty(weGroupCode.getActivityDesc())) {
            log.error("com.easywecom.wecom.service.impl.WeGroupCodeServiceImpl.checkBase: ????????????????????????????????????");
            throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
        }
        // ????????????????????????
        if (isNameOccupied(weGroupCode)) {
            throw new CustomException(ResultTip.TIP_GROUP_CODE_NAME_OCCUPIED);
        }


    }

    /**
     * ????????????????????????
     *
     * @param weGroupCode
     */
    private void buildQrUrl(WeGroupCode weGroupCode) {
        // ?????????????????????????????????????????????????????????URL
        WeCorpAccount weCorpAccount = weCorpAccountService.findValidWeCorpAccount(weGroupCode.getCorpId());
        String content = weCorpAccount.getH5DoMainName() + "/#/groupCode?id=" + weGroupCode.getId();
        try {
            String fileName = FileUploadUtils.upload2Cos(QREncode.getQRCodeMultipartFile(content, weGroupCode.getAvatarUrl()), ruoYiConfig.getFile().getCos());
            weGroupCode.setCodeUrl(ruoYiConfig.getFile().getCos().getCosImgUrlPrefix() + fileName);
        } catch (IOException e) {
            log.error("???????????????????????????: ex:{}", ExceptionUtils.getStackTrace(e));
        }
    }

    @Override
    public String getCodeUrlByIdAndCorpId(Long id, String corpId) {
        if (id == null || org.apache.commons.lang3.StringUtils.isBlank(corpId)) {
            throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
        }
        return baseMapper.getCodeUrlByIdAndCorpId(id, corpId);
    }
}
