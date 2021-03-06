package com.easywecom.wecom.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.easywecom.common.constant.WeConstans;
import com.easywecom.common.core.redis.RedisCache;
import com.easywecom.common.enums.*;
import com.easywecom.common.enums.code.WelcomeMsgTypeEnum;
import com.easywecom.common.exception.CustomException;
import com.easywecom.common.utils.DateUtils;
import com.easywecom.wecom.client.WeExternalContactClient;
import com.easywecom.wecom.domain.*;
import com.easywecom.wecom.domain.dto.AddWeMaterialDTO;
import com.easywecom.wecom.domain.dto.WeEmpleCodeDTO;
import com.easywecom.wecom.domain.dto.WeExternalContactDTO;
import com.easywecom.wecom.domain.dto.emplecode.AddWeEmpleCodeDTO;
import com.easywecom.wecom.domain.dto.emplecode.FindWeEmpleCodeDTO;
import com.easywecom.wecom.domain.entity.redeemcode.WeRedeemCode;
import com.easywecom.wecom.domain.vo.SelectWeEmplyCodeWelcomeMsgVO;
import com.easywecom.wecom.domain.vo.WeEmpleCodeVO;
import com.easywecom.wecom.domain.vo.WeEmplyCodeDownloadVO;
import com.easywecom.wecom.domain.vo.WeEmplyCodeScopeUserVO;
import com.easywecom.wecom.domain.vo.redeemcode.WeRedeemCodeActivityVO;
import com.easywecom.wecom.login.util.LoginTokenService;
import com.easywecom.wecom.mapper.WeEmpleCodeMapper;
import com.easywecom.wecom.mapper.redeemcode.WeRedeemCodeActivityMapper;
import com.easywecom.wecom.mapper.redeemcode.WeRedeemCodeMapper;
import com.easywecom.wecom.service.*;
import com.easywecom.wecom.service.redeemcode.WeRedeemCodeActivityService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ObjectUtils;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * ????????????Service???????????????
 *
 * @author Society my sister Li
 * @date 2021-11-02
 */
@Slf4j
@Service
public class WeEmpleCodeServiceImpl extends ServiceImpl<WeEmpleCodeMapper, WeEmpleCode> implements WeEmpleCodeService {

    private final WeEmpleCodeTagService weEmpleCodeTagService;
    private final WeEmpleCodeUseScopService weEmpleCodeUseScopService;
    private final WeExternalContactClient weExternalContactClient;
    private final RedisCache redisCache;
    private final WeEmpleCodeMaterialService weEmpleCodeMaterialService;
    private final WeMaterialService weMaterialService;
    private final WeGroupCodeService weGroupCodeService;
    private final WeEmpleCodeAnalyseService weEmpleCodeAnalyseService;
    private final WeGroupCodeActualService weGroupCodeActualService;
    private final WeRedeemCodeActivityMapper weRedeemCodeActivityMapper;
    private final WeRedeemCodeMapper weRedeemCodeMapper;
    private final WeRedeemCodeActivityService weRedeemCodeActivityService;
    private final WeUserService weUserService;

    @Autowired
    public WeEmpleCodeServiceImpl(WeEmpleCodeTagService weEmpleCodeTagService, WeEmpleCodeUseScopService weEmpleCodeUseScopService, WeExternalContactClient weExternalContactClient, RedisCache redisCache, WeEmpleCodeMaterialService weEmpleCodeMaterialService, WeMaterialService weMaterialService, WeGroupCodeService weGroupCodeService, WeEmpleCodeAnalyseService weEmpleCodeAnalyseService, WeGroupCodeActualService weGroupCodeActualService, WeRedeemCodeActivityMapper weRedeemCodeActivityMapper, WeRedeemCodeMapper weRedeemCodeMapper, WeRedeemCodeActivityService weRedeemCodeActivityService, WeUserService weUserService) {
        this.weEmpleCodeTagService = weEmpleCodeTagService;
        this.weEmpleCodeUseScopService = weEmpleCodeUseScopService;
        this.weExternalContactClient = weExternalContactClient;
        this.redisCache = redisCache;
        this.weEmpleCodeMaterialService = weEmpleCodeMaterialService;
        this.weMaterialService = weMaterialService;
        this.weGroupCodeService = weGroupCodeService;
        this.weEmpleCodeAnalyseService = weEmpleCodeAnalyseService;
        this.weGroupCodeActualService = weGroupCodeActualService;
        this.weRedeemCodeActivityMapper = weRedeemCodeActivityMapper;
        this.weRedeemCodeMapper = weRedeemCodeMapper;
        this.weRedeemCodeActivityService = weRedeemCodeActivityService;
        this.weUserService = weUserService;
    }

    @Override
    public WeEmpleCodeVO selectWeEmpleCodeById(Long id, String corpId) {
        if (StringUtils.isBlank(corpId) || id == null) {
            throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
        }
        WeEmpleCodeVO weEmpleCodeVO = this.baseMapper.selectWeEmpleCodeById(id, corpId);
        //????????????
        bulidWeEmpleCodeVOData(weEmpleCodeVO);
        return weEmpleCodeVO;
    }

    @Override
    public List<WeEmpleCodeVO> selectWeEmpleCodeList(FindWeEmpleCodeDTO weEmployCode) {
        if (StringUtils.isBlank(weEmployCode.getCorpId())) {
            throw new CustomException(ResultTip.TIP_MISS_CORP_ID);
        }
        //???yyyy-MM-dd??????yyyy-MM-dd HH:mm:ss
        if (StringUtils.isNotBlank(weEmployCode.getBeginTime())) {
            weEmployCode.setBeginTime(DateUtils.parseBeginDay(weEmployCode.getBeginTime()));
        }
        if (StringUtils.isNotBlank(weEmployCode.getEndTime())) {
            weEmployCode.setEndTime(DateUtils.parseEndDay(weEmployCode.getEndTime()));
        }
        List<WeEmpleCodeVO> weEmployCodeList = this.baseMapper.selectWeEmpleCodeList(weEmployCode);
        if (CollectionUtils.isEmpty(weEmployCodeList)) {
            return weEmployCodeList;
        }

        List<Long> employCodeIdList = weEmployCodeList.stream().map(WeEmpleCode::getId).collect(Collectors.toList());
        //??????????????????
        List<WeEmpleCodeTag> tagList = weEmpleCodeTagService.selectWeEmpleCodeTagListByIds(employCodeIdList);
        //???????????????
        List<WeEmpleCodeUseScop> useScopeList = weEmpleCodeUseScopService.selectWeEmpleCodeUseScopListByIds(employCodeIdList, weEmployCode.getCorpId());
        //??????????????????(???????????????????????????businessId??????we_user????????????????????????????????????businessId)
        List<WeEmpleCodeUseScop> departmentScopeList = weEmpleCodeUseScopService.selectDepartmentWeEmpleCodeUseScopListByIds(employCodeIdList);

        weEmployCodeList.forEach(employCode -> {
            //?????????????????????/????????????
            setUserData(employCode,useScopeList,departmentScopeList);
            //????????????????????????
            employCode.setWeEmpleCodeTags(tagList.stream().filter(tag -> tag.getEmpleCodeId().equals(employCode.getId())).collect(Collectors.toList()));
            //???????????????????????????=>???????????????????????????=>???????????????????????????????????????????????????
            bulidWeEmpleCodeVOData(employCode);
        });
        return weEmployCodeList;
    }

    /**
     * ???????????????????????????=>???????????????????????????=>???????????????????????????????????????????????????
     *
     * @param employCode employCode
     */
    private void bulidWeEmpleCodeVOData(WeEmpleCodeVO employCode) {
        if (!isEmplyCodeCreate(employCode.getSource())) {
            //???????????????
            int count = weEmpleCodeAnalyseService.getAddCountByState(employCode.getState());
            employCode.setCusNumber(count);

            String[] materialSort = employCode.getMaterialSort();
            if (materialSort != null && materialSort.length != 0) {
                Long groupCodeId = Long.parseLong(materialSort[0]);
                WeGroupCode groupCode = weGroupCodeService.getById(groupCodeId);
                employCode.setWeGroupCode(groupCode);
                List<WeGroupCodeActual> list = weGroupCodeActualService.selectByGroupCodeId(groupCodeId);
                employCode.setGroupList(list);
            } else {
                employCode.setWeGroupCode(new WeGroupCode());
                employCode.setGroupList(new ArrayList<>());
            }
            if (WelcomeMsgTypeEnum.REDEEM_CODE_WELCOME_MSG_TYPE.getType().equals(employCode.getWelcomeMsgType())) {
                buildEmployCodeMaterial(employCode, employCode.getCorpId());
            }
        } else {
            //????????????
            buildEmployCodeMaterial(employCode, employCode.getCorpId());
        }
    }

    /**
     * ????????????????????????????????????
     *
     * @param employCode
     * @param corpId
     */
    private void buildEmployCodeMaterial(WeEmpleCodeVO employCode, String corpId) {
        if (WelcomeMsgTypeEnum.COMMON_WELCOME_MSG_TYPE.getType().equals(employCode.getWelcomeMsgType())) {
            if (!ArrayUtils.isEmpty(employCode.getMaterialSort())) {
                List<AddWeMaterialDTO> materialList = weMaterialService.getListByMaterialSort(employCode.getMaterialSort(), corpId);
                employCode.setMaterialList(materialList);
            } else {
                employCode.setMaterialList(Collections.emptyList());
            }
        } else {
            final WeRedeemCodeActivityVO redeemCodeActivity = weRedeemCodeActivityService.getRedeemCodeActivity(corpId, Long.valueOf(employCode.getCodeActivityId()));
            employCode.setCodeActivity(Optional.ofNullable(redeemCodeActivity).orElseGet(WeRedeemCodeActivityVO::new));

            List<AddWeMaterialDTO> successMaterialList = weMaterialService.getRedeemCodeListByMaterialSort(employCode.getCodeSuccessMaterialSort(), corpId);
            employCode.setCodeSuccessMaterialList(successMaterialList);

            List<AddWeMaterialDTO> failMaterialList = weMaterialService.getRedeemCodeListByMaterialSort(employCode.getCodeFailMaterialSort(), corpId);
            employCode.setCodeFailMaterialList(failMaterialList);

            List<AddWeMaterialDTO> repeatMaterialList = weMaterialService.getRedeemCodeListByMaterialSort(employCode.getCodeRepeatMaterialSort(), corpId);
            employCode.setCodeRepeatMaterialList(repeatMaterialList);
        }
    }

    /**
     * ?????????????????????????????????
     *
     * @param source ????????????
     * @return boolean
     */
    private boolean isEmplyCodeCreate(Integer source) {
        if (source == null) {
            throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
        }
        return EmployCodeSourceEnum.CODE_CREATE.getSource().equals(source);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void insertWeEmpleCode(AddWeEmpleCodeDTO weEmpleCode) {
        //????????????
        verifyParam(weEmpleCode, weEmpleCode.getIsAutoPass(), weEmpleCode.getIsAutoSetRemark());
        weEmpleCode.setCreateTime(new Date());
        weEmpleCode.setCreateBy(LoginTokenService.getUsername());
        // ?????????????????????id??????state(???????????????????????????????????????)
        weEmpleCode.setState(weEmpleCode.getId().toString());
        addWeEmpleCode(weEmpleCode);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateWeEmpleCode(AddWeEmpleCodeDTO weEmpleCode) {
        //??????????????????
        verifyParam(weEmpleCode, weEmpleCode.getIsAutoPass(), weEmpleCode.getIsAutoSetRemark());
        if (WelcomeMsgTypeEnum.REDEEM_CODE_WELCOME_MSG_TYPE.getType().equals(weEmpleCode.getWelcomeMsgType())) {
            weEmpleCode.buildCodeMsg();
        }
        if (weEmpleCode.getId() == null) {
            throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
        }
        weEmpleCode.setUpdateTime(new Date());
        weEmpleCode.setUpdateBy(LoginTokenService.getUsername());
        Boolean isNotCreate = true;
        List<WeEmpleCodeUseScop> useScops = weEmpleCode.getWeEmpleCodeUseScops();
        //???????????????
        if (CollectionUtils.isNotEmpty(useScops) && useScops.get(0).getBusinessIdType() != null) {
            weEmpleCodeUseScopService.remove(new LambdaUpdateWrapper<WeEmpleCodeUseScop>().eq(WeEmpleCodeUseScop::getEmpleCodeId, weEmpleCode.getId()));
            useScops.forEach(item -> item.setEmpleCodeId(weEmpleCode.getId()));
            weEmpleCodeUseScopService.saveOrUpdateBatch(useScops);
            //??????????????????
            WeExternalContactDTO.WeContactWay weContactWay = getWeContactWay(weEmpleCode);
            WeExternalContactDTO contactDTO = getQrCodeFromClient(weContactWay, weEmpleCode.getCorpId());
            weEmpleCode.setConfigId(contactDTO.getConfig_id());
            weEmpleCode.setQrCode(contactDTO.getQr_code());
            isNotCreate = false;
        }

        //????????????
        weEmpleCodeTagService.remove(new LambdaUpdateWrapper<WeEmpleCodeTag>().eq(WeEmpleCodeTag::getEmpleCodeId, weEmpleCode.getId()));
        if (CollectionUtils.isNotEmpty(weEmpleCode.getWeEmpleCodeTags())) {
            weEmpleCode.getWeEmpleCodeTags().forEach(item -> item.setEmpleCodeId(weEmpleCode.getId()));
            weEmpleCodeTagService.saveOrUpdateBatch(weEmpleCode.getWeEmpleCodeTags());
        }
        if (isEmplyCodeCreate(weEmpleCode.getSource())) {
            weEmpleCodeMaterialService.remove(new LambdaUpdateWrapper<WeEmpleCodeMaterial>().eq(WeEmpleCodeMaterial::getEmpleCodeId, weEmpleCode.getId()));
            buildMaterialSort(weEmpleCode);
        } else {
            weEmpleCodeMaterialService.remove(new LambdaUpdateWrapper<WeEmpleCodeMaterial>().eq(WeEmpleCodeMaterial::getEmpleCodeId, weEmpleCode.getId()));
            if (WelcomeMsgTypeEnum.REDEEM_CODE_WELCOME_MSG_TYPE.getType().equals(weEmpleCode.getWelcomeMsgType())) {
                buildMaterialSort(weEmpleCode);
            }
            weEmpleCode.setMaterialSort(new String[]{weEmpleCode.getGroupCodeId().toString()});
            //???????????????????????????
            saveGroupCodeMaterial(weEmpleCode.getId(), weEmpleCode.getGroupCodeId());
        }

        List<Long> activityIdList = new ArrayList<>();
        activityIdList.add(weEmpleCode.getId());
        //???????????????
        weEmpleCodeMaterialService.removeByEmpleCodeId(activityIdList);
        //?????????????????????????????????
        this.baseMapper.deleteWeEmpleCode(weEmpleCode.getCorpId(), weEmpleCode.getId());
        this.baseMapper.insertWeEmpleCode(weEmpleCode);
        //??????????????????????????????
        if (isNotCreate) {
            WeExternalContactDTO.WeContactWay weContactWay = getWeContactWay(weEmpleCode);
            weExternalContactClient.updateContactWay(weContactWay, weEmpleCode.getCorpId());
        }
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchRemoveWeEmpleCodeIds(String corpId, List<Long> ids) {
        if (StringUtils.isBlank(corpId) || CollectionUtils.isEmpty(ids)) {
            throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
        }
        //???????????????
        weEmpleCodeMaterialService.removeByEmpleCodeId(ids);
        return this.baseMapper.batchRemoveWeEmpleCodeIds(corpId, ids);
    }

    @Override
    public WeEmpleCodeDTO selectWelcomeMsgByScenario(String scenario, String userId, String corpId) {
        if (StringUtils.isBlank(corpId)) {
            throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
        }
        return this.baseMapper.selectWelcomeMsgByScenario(scenario, userId, corpId);
    }


    /**
     * ?????????????????????
     *
     * @param corpId
     * @param externalUserId
     * @param welcomeMsgVO
     * @return
     */
    private void buildMaterial(String corpId, String externalUserId, SelectWeEmplyCodeWelcomeMsgVO welcomeMsgVO) {
        WeRedeemCode weRedeemCodeDTO = WeRedeemCode.builder().activityId(welcomeMsgVO.getCodeActivityId()).effectiveTime(DateUtils.getDate()).build();
        weRedeemCodeDTO.setCorpId(corpId);
        //?????????????????????????????????????????????
        WeRedeemCode getRedeemCode = weRedeemCodeMapper.selectOneWhenInEffective(weRedeemCodeDTO);
        //????????????0
        if (!ObjectUtils.isEmpty(getRedeemCode)) {
            //???????????????????????????????????????, ????????????????????????????????????
            WeRedeemCode weRedeemCode = WeRedeemCode.builder().activityId(welcomeMsgVO.getCodeActivityId()).receiveUserId(externalUserId).build();
            final WeRedeemCode selectWeRedeemCode = weRedeemCodeMapper.selectOne(weRedeemCode);
            //?????????????????????????????????
            if (ObjectUtils.isEmpty(selectWeRedeemCode)) {
                welcomeMsgVO.setMaterialList(weMaterialService.getRedeemCodeListByMaterialSort(welcomeMsgVO.getCodeSuccessMaterialSort(), corpId));
                welcomeMsgVO.setRedeemCode(getRedeemCode.getCode());
                welcomeMsgVO.setWelcomeMsg(welcomeMsgVO.getCodeSuccessMsg());
            } else {
                //???????????????????????????
                final WeRedeemCodeActivityVO redeemCodeActivity = weRedeemCodeActivityService.getRedeemCodeActivity(corpId, Long.valueOf(welcomeMsgVO.getCodeActivityId()));
                if (WeConstans.REDEEM_CODE_ACTIVITY_LIMITED.equals(redeemCodeActivity.getEnableLimited())) {
                    welcomeMsgVO.setMaterialList(weMaterialService.getRedeemCodeListByMaterialSort(welcomeMsgVO.getCodeRepeatMaterialSort(), corpId));
                    welcomeMsgVO.setWelcomeMsg(welcomeMsgVO.getCodeRepeatMsg());
                } else {
                    welcomeMsgVO.setMaterialList(weMaterialService.getRedeemCodeListByMaterialSort(welcomeMsgVO.getCodeSuccessMaterialSort(), corpId));
                    welcomeMsgVO.setWelcomeMsg(welcomeMsgVO.getCodeSuccessMsg());
                    welcomeMsgVO.setRedeemCode(getRedeemCode.getCode());
                }
            }
        } else {
            //??????????????????
            welcomeMsgVO.setMaterialList(weMaterialService.getRedeemCodeListByMaterialSort(welcomeMsgVO.getCodeFailMaterialSort(), corpId));
            welcomeMsgVO.setWelcomeMsg(welcomeMsgVO.getCodeFailMsg());
        }
    }

    @Override
    public SelectWeEmplyCodeWelcomeMsgVO selectWelcomeMsgByState(String state, String corpId, String externalUserId) {
        if (StringUtils.isBlank(state) || StringUtils.isBlank(corpId)) {
            throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
        }
        SelectWeEmplyCodeWelcomeMsgVO welcomeMsgVO = this.baseMapper.selectWelcomeMsgByState(state, corpId);
        if (welcomeMsgVO == null) {
            return null;
        }
        if (welcomeMsgVO.getMaterialSort() != null && welcomeMsgVO.getMaterialSort().length != 0) {
            //??????????????????????????????????????????
            if (isEmplyCodeCreate(welcomeMsgVO.getSource())) {
                welcomeMsgVO.setMaterialList(weMaterialService.getListByMaterialSort(welcomeMsgVO.getMaterialSort(), corpId));
            } else {
                //??????????????????we_group_code?????????url
                String groupCodeId = welcomeMsgVO.getMaterialSort()[0];
                String codeUrl = weGroupCodeService.getCodeUrlByIdAndCorpId(Long.parseLong(groupCodeId), corpId);
                welcomeMsgVO.setGroupCodeUrl(codeUrl);
            }
        }
        if (WelcomeMsgTypeEnum.REDEEM_CODE_WELCOME_MSG_TYPE.getType().equals(welcomeMsgVO.getWelcomeMsgType())) {
            buildMaterial(corpId, externalUserId, welcomeMsgVO);
        }

        return welcomeMsgVO;
    }

    @Override
    public WeExternalContactDTO getQrcode(String userIds, String departmentIds, String corpId) {
        String[] userIdArr = Arrays.stream(userIds.split(",")).filter(StringUtils::isNotEmpty).toArray(String[]::new);
        Long[] departmentIdArr = Arrays.stream(departmentIds.split(",")).filter(StringUtils::isNotEmpty).map(Long::new).toArray(Long[]::new);
        WeExternalContactDTO qrcode = getQrcode(userIdArr, departmentIdArr, corpId);
        //??????24????????????
        log.info("qrcode:>>>>>>>>>>>???{}???", JSON.toJSONString(qrcode));
        if (qrcode != null && qrcode.getConfig_id() != null) {
            redisCache.setCacheObject(WeConstans.getWeEmployCodeKey(corpId, qrcode.getConfig_id()), qrcode.getConfig_id(), 24, TimeUnit.HOURS);
        }
        return qrcode;
    }

    @Override
    public WeExternalContactDTO getQrcode(String[] userIdArr, Long[] departmentIdArr, String corpId) {
        WeExternalContactDTO.WeContactWay weContactWay = new WeExternalContactDTO.WeContactWay();
        //???????????????id????????????id???????????????????????????????????????
        if (departmentIdArr.length > 0 || userIdArr.length > 1) {
            weContactWay.setType(WeConstans.MANY_EMPLE_CODE_TYPE);
        } else {
            weContactWay.setType(WeConstans.SINGLE_EMPLE_CODE_TYPE);
        }
        weContactWay.setScene(WeConstans.QR_CODE_EMPLE_CODE_SCENE);
        weContactWay.setUser(userIdArr);
        weContactWay.setParty(departmentIdArr);
        return getQrCodeFromClient(weContactWay, corpId);
    }

    /**
     * ???????????????
     *
     * @param weContactWay ????????????
     * @param corpId       ??????ID
     * @return WeExternalContactDTO
     */
    private WeExternalContactDTO getQrCodeFromClient(WeExternalContactDTO.WeContactWay weContactWay, String corpId) {
        return weExternalContactClient.addContactWay(weContactWay, corpId);
    }

    /**
     * ??????????????????
     *
     * @param weEmpleCode weEmpleCode
     */
    private void addWeEmpleCode(AddWeEmpleCodeDTO weEmpleCode) {
        if (WelcomeMsgTypeEnum.REDEEM_CODE_WELCOME_MSG_TYPE.getType().equals(weEmpleCode.getWelcomeMsgType())) {
            weEmpleCode.buildCodeMsg();
        }
        //??????????????????
        WeExternalContactDTO.WeContactWay weContactWay = getWeContactWay(weEmpleCode);
        WeExternalContactDTO contactDTO = getQrCodeFromClient(weContactWay, weEmpleCode.getCorpId());
        weEmpleCode.setConfigId(contactDTO.getConfig_id());
        weEmpleCode.setQrCode(contactDTO.getQr_code());

        //????????????????????????
        weEmpleCode.getWeEmpleCodeUseScops().forEach(item -> item.setEmpleCodeId(weEmpleCode.getId()));
        weEmpleCodeUseScopService.saveBatch(weEmpleCode.getWeEmpleCodeUseScops());
        //??????????????????
        if (CollectionUtils.isNotEmpty(weEmpleCode.getWeEmpleCodeTags())) {
            weEmpleCode.getWeEmpleCodeTags().forEach(item -> item.setEmpleCodeId(weEmpleCode.getId()));
            weEmpleCodeTagService.saveBatch(weEmpleCode.getWeEmpleCodeTags());
        }
        if (isEmplyCodeCreate(weEmpleCode.getSource())) {
            buildMaterialSort(weEmpleCode);
        } else {
            weEmpleCode.setMaterialSort(new String[]{weEmpleCode.getGroupCodeId().toString()});
            //???????????????????????????
            saveGroupCodeMaterial(weEmpleCode.getId(), weEmpleCode.getGroupCodeId());
            buildMaterialSort(weEmpleCode);
        }
        baseMapper.insertWeEmpleCode(weEmpleCode);
    }

    /**
     * ????????????
     *
     * @param codeMaterialList
     * @param weEmpleCode
     */
    private void setMaterialSort(List<AddWeMaterialDTO> codeMaterialList, WeEmpleCode weEmpleCode) {
        //???????????????????????????????????????,?????????????????????tempFlag=1
        saveTempMaterial(codeMaterialList);
        //????????????????????????
        saveEmpleCodeMaterialList(codeMaterialList, weEmpleCode.getId());
    }

    /**
     * ??????????????????
     *
     * @param weEmpleCode
     */
    private void buildMaterialSort(AddWeEmpleCodeDTO weEmpleCode) {
        if (WelcomeMsgTypeEnum.COMMON_WELCOME_MSG_TYPE.getType().equals(weEmpleCode.getWelcomeMsgType())) {
            //??????????????????
            if (CollectionUtils.isNotEmpty(weEmpleCode.getMaterialList())) {
                final List<AddWeMaterialDTO> materialList = weEmpleCode.getMaterialList();
                setMaterialSort(materialList, weEmpleCode);
                //??????????????????????????????weEmpleCode
                weEmpleCode.setMaterialSort(getMaterialSort(materialList));
            }
        } else if (WelcomeMsgTypeEnum.REDEEM_CODE_WELCOME_MSG_TYPE.getType().equals(weEmpleCode.getWelcomeMsgType())) {
            if (CollectionUtils.isNotEmpty(weEmpleCode.getCodeSuccessMaterialList())) {
                final List<AddWeMaterialDTO> materialList = weEmpleCode.getCodeSuccessMaterialList();
                setMaterialSort(materialList, weEmpleCode);
                weEmpleCode.setCodeSuccessMaterialSort(getMaterialSort(materialList));
            }
            if (CollectionUtils.isNotEmpty(weEmpleCode.getCodeFailMaterialList())) {
                final List<AddWeMaterialDTO> materialList = weEmpleCode.getCodeFailMaterialList();
                setMaterialSort(materialList, weEmpleCode);
                weEmpleCode.setCodeFailMaterialSort(getMaterialSort(materialList));
            }
            if (CollectionUtils.isNotEmpty(weEmpleCode.getCodeRepeatMaterialList())) {
                final List<AddWeMaterialDTO> materialList = weEmpleCode.getCodeRepeatMaterialList();
                setMaterialSort(materialList, weEmpleCode);
                weEmpleCode.setCodeRepeatMaterialSort(getMaterialSort(materialList));
            }
        }
    }

    /**
     * ?????????ID?????????,?????????????????? ?????????????????????????????????ID
     *
     * @param materialDTOList materialDTOList
     */
    private void saveTempMaterial(List<AddWeMaterialDTO> materialDTOList) {
        if (CollectionUtils.isNotEmpty(materialDTOList)) {
            for (AddWeMaterialDTO materialDTO : materialDTOList) {
                if (materialDTO.getId() == null) {
                    materialDTO.setTempFlag(WeTempMaterialEnum.TEMP.getTempFlag());
                    weMaterialService.insertWeMaterial(materialDTO);
                }
            }
        }
    }

    /**
     * ????????????????????????
     *
     * @param materialDTOList ????????????
     * @param weEmpleCodeId   ????????????ID
     */
    private void saveEmpleCodeMaterialList(List<AddWeMaterialDTO> materialDTOList, Long weEmpleCodeId) {
        List<WeEmpleCodeMaterial> addList = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(materialDTOList)) {
            for (AddWeMaterialDTO weEmpleCodeMaterialDTO : materialDTOList) {
                if (weEmpleCodeMaterialDTO.getId() == null || weEmpleCodeMaterialDTO.getMediaType() == null) {
                    throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
                }
                addList.add(new WeEmpleCodeMaterial(weEmpleCodeId, weEmpleCodeMaterialDTO.getId(), weEmpleCodeMaterialDTO.getMediaType()));
            }
            weEmpleCodeMaterialService.batchInsert(addList);
        }
    }

    /**
     * ???????????????ID????????????
     *
     * @param weEmpleCodeId ????????????ID
     * @param groupCodeId   ?????????ID
     */
    private void saveGroupCodeMaterial(Long weEmpleCodeId, Long groupCodeId) {
        WeEmpleCodeMaterial weEmpleCodeMaterial = new WeEmpleCodeMaterial(weEmpleCodeId, groupCodeId, WeConstans.DEFAULT_GROUP_CODE_MEDIA_TYPE);
        weEmpleCodeMaterialService.save(weEmpleCodeMaterial);
    }


    /**
     * ???????????????????????????????????????????????????????????????
     *
     * @param weEmpleCode ?????????????????????
     * @return ???????????????????????????
     */
    private WeExternalContactDTO.WeContactWay getWeContactWay(WeEmpleCode weEmpleCode) {
        WeExternalContactDTO.WeContactWay weContactWay = new WeExternalContactDTO.WeContactWay();
        List<WeEmpleCodeUseScop> weEmpleCodeUseScops = weEmpleCode.getWeEmpleCodeUseScops();
        //?????????????????????????????????
        weContactWay.setConfig_id(weEmpleCode.getConfigId());
        weContactWay.setType(weEmpleCode.getCodeType());
        weContactWay.setScene(WeConstans.QR_CODE_EMPLE_CODE_SCENE);
        weContactWay.setSkip_verify(WeEmployCodeSkipVerifyEnum.isPassByNow(weEmpleCode.getSkipVerify(), weEmpleCode.getEffectTimeOpen(), weEmpleCode.getEffectTimeClose()));
        weContactWay.setState(weEmpleCode.getState());

        List<String> userIdList = new LinkedList<>();
        List<Long> partyIdList = new LinkedList<>();

        if (CollUtil.isNotEmpty(weEmpleCodeUseScops)) {
            weEmpleCodeUseScops.forEach(item -> {
                //????????????
                if (WeConstans.USE_SCOP_BUSINESSID_TYPE_USER.equals(item.getBusinessIdType())
                        && StringUtils.isNotEmpty(item.getBusinessId())) {
                    userIdList.add(item.getBusinessId());
                }
                //????????????
                if (!WeConstans.SINGLE_EMPLE_CODE_TYPE.equals(weEmpleCode.getCodeType())
                        && WeConstans.USE_SCOP_BUSINESSID_TYPE_ORG.equals(item.getBusinessIdType())) {
                    //partyIdList.add(Long.valueOf(item.getBusinessId()));
                    //?????????????????????
                    List<String> userIdsByDepartment = weUserService.listOfUserId(weEmpleCode.getCorpId(), new String[]{item.getBusinessId()});
                    if (CollectionUtils.isNotEmpty(userIdsByDepartment)) {
                        userIdList.addAll(userIdsByDepartment);
                    }
                }
            });
            String[] userIdArr = userIdList.toArray(new String[]{});
            weContactWay.setUser(userIdArr);
            Long[] partyArr = partyIdList.toArray(new Long[]{});
            weContactWay.setParty(partyArr);
        }
        return weContactWay;
    }

    @Override
    public List<WeEmpleCode> getWeEmpleCodeByEffectTime(String HHmm) {
        if (StringUtils.isBlank(HHmm)) {
            throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
        }
        return baseMapper.getWeEmpleCodeByTime(HHmm);
    }

    @Override
    public List<WeEmplyCodeDownloadVO> downloadWeEmplyCodeData(String corpId, List<Long> idList) {
        if (StringUtils.isBlank(corpId) || CollectionUtils.isEmpty(idList)) {
            throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
        }
        return baseMapper.downloadWeEmplyCodeData(corpId, idList);
    }

    @Override
    public List<WeEmplyCodeScopeUserVO> getUserByEmplyCode(String corpId, Long id) {
        if (StringUtils.isBlank(corpId) || id == null) {
            throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
        }
        List<WeEmplyCodeScopeUserVO> users = baseMapper.getUserByEmplyCodeId(corpId, id);
        List<WeEmplyCodeScopeUserVO> usersFromDepartment = baseMapper.getUserFromDepartmentByEmplyCodeId(corpId, id);
        return baseMapper.getUserByEmplyCodeId(corpId, id);
    }

    /**
     * ????????????????????????????????????
     *
     * @param employCode            ??????????????????
     * @param useScopeList          ???????????????
     * @param departmentScopeList   ??????????????????
     */
    private void setUserData(WeEmpleCodeVO employCode, List<WeEmpleCodeUseScop> useScopeList, List<WeEmpleCodeUseScop> departmentScopeList) {
        List<WeEmpleCodeUseScop> setUseScopeList = new LinkedList<>();
        if(CollUtil.isNotEmpty(useScopeList)){
            StringBuilder userUserName = new StringBuilder();
            StringBuilder mobile = new StringBuilder();
            useScopeList.forEach(useScope->{
                if(useScope.getEmpleCodeId().equals(employCode.getId())
                        && WeConstans.USE_SCOP_BUSINESSID_TYPE_USER.equals(useScope.getBusinessIdType())
                        && StringUtils.isNotEmpty(useScope.getBusinessName())){
                    userUserName.append(useScope.getBusinessName()).append(WeConstans.COMMA);
                    mobile.append(useScope.getMobile()).append(WeConstans.COMMA);
                    setUseScopeList.add(useScope);
                }
            });
            if(StringUtils.isNotEmpty(userUserName)){
                //??????????????????","
                userUserName.deleteCharAt(userUserName.length()-1);
            }
            if(StringUtils.isNotEmpty(mobile)){
                //??????????????????","
                mobile.deleteCharAt(mobile.length()-1);
            }
            employCode.setUseUserName(userUserName.toString());
            employCode.setMobile(mobile.toString());
        }
        if(CollUtil.isNotEmpty(departmentScopeList)){
            StringBuilder departmentName = new StringBuilder();
            departmentScopeList.forEach(departScope->{
                if(departScope.getEmpleCodeId().equals(employCode.getId())
                        && WeConstans.USE_SCOP_BUSINESSID_TYPE_ORG.equals(departScope.getBusinessIdType())
                        && StringUtils.isNotEmpty(departScope.getBusinessName())){
                    departmentName.append(departScope.getBusinessName()).append(WeConstans.COMMA);
                    setUseScopeList.add(departScope);
                }
            });
            if(StringUtils.isNotEmpty(departmentName)){
                //??????????????????","
                departmentName.deleteCharAt(departmentName.length()-1);
            }
            employCode.setDepartmentName(departmentName.toString());
        }
        employCode.setWeEmpleCodeUseScops(setUseScopeList);
    }


    /**
     * ????????????????????????????????????
     *
     * @param materialList materialList
     * @return String[]
     */
    private static String[] getMaterialSort(List<AddWeMaterialDTO> materialList) {
        if (CollectionUtils.isEmpty(materialList)) {
            return new String[]{};
        }
        List<Long> collect = materialList.stream().map(AddWeMaterialDTO::getId).collect(Collectors.toList());
        return StringUtils.join(collect, ",").split(",");
    }

    /**
     * ?????????????????? ??????????????????
     *
     * @param weEmpleCode     weEmpleCode
     * @param isAutoPass      ??????????????????
     * @param isAutoSetRemark ??????????????????
     */
    private void verifyParam(AddWeEmpleCodeDTO weEmpleCode, Boolean isAutoPass, Boolean isAutoSetRemark) {
        if (weEmpleCode == null
                || StringUtils.isBlank(weEmpleCode.getCorpId())
                || weEmpleCode.getCodeType() == null
                || weEmpleCode.getSkipVerify() == null
                || StringUtils.isBlank(weEmpleCode.getScenario())
                || weEmpleCode.getRemarkType() == null
                || weEmpleCode.getWeEmpleCodeUseScops() == null
                || weEmpleCode.getWeEmpleCodeUseScops().size() == 0
                || CollectionUtils.isEmpty(weEmpleCode.getWeEmpleCodeUseScops())
                || weEmpleCode.getSource() == null) {
            throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
        }
        //?????????????????????????????????????????????
        if (WelcomeMsgTypeEnum.REDEEM_CODE_WELCOME_MSG_TYPE.getType().equals(weEmpleCode.getWelcomeMsgType())) {
            if (ObjectUtils.isEmpty(weEmpleCode.getCodeActivity())) {
                throw new CustomException(ResultTip.TIP_REDEEM_CODE_ACTIVITY_IS_EMPTY);
            } else {
                if (Long.valueOf(0).equals(weEmpleCode.getCodeActivity().getId())) {
                    throw new CustomException(ResultTip.TIP_REDEEM_CODE_ACTIVITY_IS_EMPTY);
                }
            }
            if (StringUtils.isAllBlank(weEmpleCode.getCodeSuccessMsg(), weEmpleCode.getCodeFailMsg(), weEmpleCode.getCodeRepeatMsg())) {
                throw new CustomException(ResultTip.TIP_REDEEM_CODE_WELCOME_MSG_IS_EMPTY);
            }
        }

        //??????????????????????????????????????????????????????
        if (WeEmployCodeSkipVerifyEnum.TIME_PASS.getSkipVerify().equals(weEmpleCode.getSkipVerify())) {
            if (StringUtils.isBlank(weEmpleCode.getEffectTimeOpen()) || StringUtils.isBlank(weEmpleCode.getEffectTimeClose())) {
                throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
            }
            //???????????????????????????????????????
            if (weEmpleCode.getEffectTimeOpen().equals(weEmpleCode.getEffectTimeClose())) {
                throw new CustomException(ResultTip.TIP_TIME_RANGE_FORMAT_ERROR);
            }
        }
        if (isAutoPass != null && !isAutoPass) {
            weEmpleCode.setSkipVerify(WeEmployCodeSkipVerifyEnum.NO_PASS.getSkipVerify());
        }
        if (isAutoSetRemark != null && !isAutoSetRemark) {
            weEmpleCode.setRemarkType(WeEmployCodeRemarkTypeEnum.NO.getRemarkType());
        }

        //??????????????????????????????,remarkName????????????
        if (!WeEmployCodeRemarkTypeEnum.NO.getRemarkType().equals(weEmpleCode.getRemarkType()) && StringUtils.isBlank(weEmpleCode.getRemarkName())) {
            throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
        }
        //???????????????????????????????????????9???
        if (isEmplyCodeCreate(weEmpleCode.getSource())) {
            //???????????????????????????
            if (CollectionUtils.isNotEmpty(weEmpleCode.getMaterialList()) && weEmpleCode.getMaterialList().size() > WeConstans.MAX_ATTACHMENT_NUM) {
                throw new CustomException(ResultTip.TIP_ATTACHMENT_OVER);
            }
        } else {
            //???????????????????????????groupCodeId??????
            if (weEmpleCode.getGroupCodeId() == null) {
                throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
            }
        }
    }

}
