package com.easywecom.wecom.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.easywecom.common.constant.WeConstans;
import com.easywecom.common.enums.ResultTip;
import com.easywecom.common.enums.WeOperationsCenterSop;
import com.easywecom.common.exception.CustomException;
import com.easywecom.common.utils.SnowFlakeUtil;
import com.easywecom.common.utils.bean.BeanUtils;
import com.easywecom.wecom.domain.WeOperationsCenterSopMaterialEntity;
import com.easywecom.wecom.domain.WeOperationsCenterSopRulesEntity;
import com.easywecom.wecom.domain.WeWordsDetailEntity;
import com.easywecom.wecom.domain.dto.groupsop.AddWeOperationsCenterSopRuleDTO;
import com.easywecom.wecom.domain.vo.sop.SopRuleVO;
import com.easywecom.wecom.mapper.WeOperationsCenterSopRulesMapper;
import com.easywecom.wecom.service.WeOperationsCenterSopMaterialService;
import com.easywecom.wecom.service.WeOperationsCenterSopRulesService;
import com.easywecom.wecom.service.WeWordsDetailService;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;


@Service
@Validated
public class WeOperationsCenterSopRulesServiceImpl extends ServiceImpl<WeOperationsCenterSopRulesMapper, WeOperationsCenterSopRulesEntity> implements WeOperationsCenterSopRulesService {

    private final WeWordsDetailService weWordsDetailService;
    private final WeOperationsCenterSopMaterialService sopMaterialService;

    @Autowired
    public WeOperationsCenterSopRulesServiceImpl(WeWordsDetailService weWordsDetailService, WeOperationsCenterSopMaterialService sopMaterialService) {
        this.weWordsDetailService = weWordsDetailService;
        this.sopMaterialService = sopMaterialService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchSaveRuleAndMaterialList(Long sopId, String corpId, List<AddWeOperationsCenterSopRuleDTO> ruleList) {
        if (sopId == null || StringUtils.isBlank(corpId) || CollectionUtils.isEmpty(ruleList)) {
            throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
        }
        WeOperationsCenterSopRulesEntity sopRulesEntity;
        for (AddWeOperationsCenterSopRuleDTO sopRuleDTO : ruleList) {
            checkParam(sopRuleDTO);
            //????????????
            sopRulesEntity = new WeOperationsCenterSopRulesEntity();
            BeanUtils.copyProperties(sopRuleDTO, sopRulesEntity);
            sopRulesEntity.setCorpId(corpId);
            sopRulesEntity.setSopId(sopId);
            this.save(sopRulesEntity);
            //??????????????????
            batchSaveMaterial(corpId, sopId, sopRulesEntity.getId(), sopRuleDTO.getMaterialList());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delSopByCorpIdAndSopIdList(String corpId, List<Long> sopIdList) {
        if (StringUtils.isBlank(corpId) || CollectionUtils.isEmpty(sopIdList)) {
            throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
        }
        //??????????????????????????????
        sopMaterialService.delSopByCorpIdAndSopIdList(corpId, sopIdList);

        LambdaQueryWrapper<WeOperationsCenterSopRulesEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WeOperationsCenterSopRulesEntity::getCorpId, corpId)
                .in(WeOperationsCenterSopRulesEntity::getSopId, sopIdList);
        baseMapper.delete(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateSopRules(String corpId, Long sopId, List<AddWeOperationsCenterSopRuleDTO> ruleList, List<Long> delList) {
        if (StringUtils.isBlank(corpId) || sopId == null) {
            throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
        }
        //?????????????????????
        if (CollectionUtils.isNotEmpty(delList)) {
            delSopByCorpIdAndIdList(corpId, delList);
        }
        List<Long> delMaterialList = new ArrayList<>();
        WeOperationsCenterSopRulesEntity sopRulesEntity;
        List<WeWordsDetailEntity> updateMaterialList = new ArrayList<>();
        List<WeOperationsCenterSopRulesEntity> updateList = new ArrayList<>();
        for (AddWeOperationsCenterSopRuleDTO sopRuleDTO : ruleList) {
            checkParam(sopRuleDTO);

            //?????????????????????????????????????????????delMaterialList????????????
            if (CollectionUtils.isNotEmpty(sopRuleDTO.getDelMaterialList())) {
                delMaterialList.addAll(sopRuleDTO.getDelMaterialList());
            }

            sopRulesEntity = new WeOperationsCenterSopRulesEntity();
            BeanUtils.copyProperties(sopRuleDTO, sopRulesEntity);
            sopRulesEntity.setCorpId(corpId);
            sopRulesEntity.setSopId(sopId);

            //??????????????????
            if (sopRuleDTO.getId() == null) {
                baseMapper.insert(sopRulesEntity);
                //????????????????????????
                batchSaveMaterial(corpId, sopId, sopRulesEntity.getId(), sopRuleDTO.getMaterialList());
            } else {
                updateList.add(sopRulesEntity);
                if (CollectionUtils.isNotEmpty(sopRuleDTO.getMaterialList())) {
                    sopRuleDTO.getMaterialList().forEach(sopMaterial -> sopMaterial.setRuleId(sopRuleDTO.getId()));
                    updateMaterialList.addAll(sopRuleDTO.getMaterialList());
                }
            }
        }

        //???????????????????????????
        if (CollectionUtils.isNotEmpty(delMaterialList)) {
            weWordsDetailService.delByCorpIdAndIdList(corpId, delMaterialList);
        }
        //?????????????????????
        if (CollectionUtils.isNotEmpty(updateList)) {
            baseMapper.batchUpdate(corpId, updateList);
        }
        //???????????????????????????
        List<WeOperationsCenterSopMaterialEntity> sopMaterialEntityList = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(updateMaterialList)) {
            updateMaterialList.forEach(material ->{
                material.setCorpId(corpId);
                material.setGroupId(WeConstans.DEFAULT_SOP_WORDS_DETAIL_GROUP_ID);
                if (material.getId() == null) {
                    material.setId(SnowFlakeUtil.nextId());
                    //????????????????????????
                    WeOperationsCenterSopMaterialEntity sopMaterialEntity = new WeOperationsCenterSopMaterialEntity();
                    buildSopMaterialEntity(sopMaterialEntity, sopMaterialEntityList, corpId, sopId, material.getRuleId(), material.getId());
                }
            });
            weWordsDetailService.saveOrUpdate(updateMaterialList);
            //??????????????????sop?????????
            sopMaterialService.saveOrUpdateBatch(sopMaterialEntityList);
        }

    }

    @Override
    public SopRuleVO getSopRule(@NotEmpty String corpId, @NotNull Long sopId, @NotNull Long id) {
        return baseMapper.getSopRule(corpId, sopId, id);
    }

    /**
     * ??????corpId???idList????????????
     *
     * @param corpId  ??????ID
     * @param delList ??????????????????
     */
    private void delSopByCorpIdAndIdList(String corpId, List<Long> delList) {
        if (StringUtils.isBlank(corpId) || CollectionUtils.isEmpty(delList)) {
            throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
        }
        LambdaQueryWrapper<WeOperationsCenterSopRulesEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WeOperationsCenterSopRulesEntity::getCorpId, corpId)
                .in(WeOperationsCenterSopRulesEntity::getId, delList);
        baseMapper.delete(wrapper);
    }


    /**
     * ??????????????????
     *
     * @param corpId       ??????ID
     * @param sopId        sopId
     * @param ruleId       ??????ID
     * @param materialList ????????????
     */
    private void batchSaveMaterial(String corpId, Long sopId, Long ruleId, List<WeWordsDetailEntity> materialList) {
        if (StringUtils.isBlank(corpId) || sopId == null || ruleId == null || CollectionUtils.isEmpty(materialList)) {
            throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
        }
        List<WeWordsDetailEntity> addList = new ArrayList<>();
        List<WeOperationsCenterSopMaterialEntity> sopMaterialEntityList = new ArrayList<>();
        WeOperationsCenterSopMaterialEntity sopMaterialEntity;
        for (WeWordsDetailEntity detailEntity : materialList) {
            if (detailEntity.getId() == null) {
                detailEntity.setId(SnowFlakeUtil.nextId());
                detailEntity.setCorpId(corpId);
                detailEntity.setGroupId(WeConstans.DEFAULT_SOP_WORDS_DETAIL_GROUP_ID);
                addList.add(detailEntity);
            }
            sopMaterialEntity = new WeOperationsCenterSopMaterialEntity();
            buildSopMaterialEntity(sopMaterialEntity, sopMaterialEntityList, corpId, sopId, ruleId, detailEntity.getId());
        }
        //??????????????????
        if (CollectionUtils.isNotEmpty(addList)) {
            weWordsDetailService.saveOrUpdate(addList);
        }
        //?????????sop?????????
        sopMaterialService.saveBatch(sopMaterialEntityList);
    }

    /**
     * ??????sop????????????
     */
    private void buildSopMaterialEntity(WeOperationsCenterSopMaterialEntity sopMaterialEntity, List<WeOperationsCenterSopMaterialEntity> sopMaterialEntityList, String corpId, Long sopId, Long ruleId, Long materialId) {
        sopMaterialEntity.setCorpId(corpId);
        sopMaterialEntity.setMaterialId(materialId);
        sopMaterialEntity.setRuleId(ruleId);
        sopMaterialEntity.setSopId(sopId);
        sopMaterialEntityList.add(sopMaterialEntity);
    }

    /**
     * ??????????????????
     *
     * @param sopRuleDTO sopRuleDTO
     */
    private void checkParam(AddWeOperationsCenterSopRuleDTO sopRuleDTO) {
        if (sopRuleDTO == null || StringUtils.isBlank(sopRuleDTO.getName())
                || sopRuleDTO.getAlertType() == null || CollectionUtils.isEmpty(sopRuleDTO.getMaterialList())
                || StringUtils.isBlank(sopRuleDTO.getAlertData2())) {
            throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
        }
        //???alertType=5???(??????SOP)??????alertData1?????????
        if (WeOperationsCenterSop.AlertTypeEnum.TYPE_5.getAlertType().equals(sopRuleDTO.getAlertType())) {
            sopRuleDTO.setAlertData1(WeConstans.DEFAULT_SOP_ALTER_DATA1);
        }
        //??????alertType??????alertData1???alertData2
        WeOperationsCenterSop.AlertTypeEnum.checkParam(sopRuleDTO.getAlertType(), sopRuleDTO.getAlertData1(), sopRuleDTO.getAlertData2());
    }

}