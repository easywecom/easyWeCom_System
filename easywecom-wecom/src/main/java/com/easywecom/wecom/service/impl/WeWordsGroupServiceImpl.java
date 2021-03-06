package com.easywecom.wecom.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.easywecom.common.config.CosConfig;
import com.easywecom.common.config.RuoYiConfig;
import com.easywecom.common.constant.GenConstants;
import com.easywecom.common.constant.WeConstans;
import com.easywecom.common.core.domain.entity.WeCorpAccount;
import com.easywecom.common.core.domain.model.LoginUser;
import com.easywecom.common.enums.MessageType;
import com.easywecom.common.enums.ResultTip;
import com.easywecom.common.enums.WeWordsCategoryTypeEnum;
import com.easywecom.common.exception.CustomException;
import com.easywecom.common.utils.StringUtils;
import com.easywecom.common.utils.file.FileUploadUtils;
import com.easywecom.common.utils.poi.ExcelUtil;
import com.easywecom.common.utils.spring.SpringUtils;
import com.easywecom.wecom.client.WeMessagePushClient;
import com.easywecom.wecom.domain.WeWordsCategory;
import com.easywecom.wecom.domain.WeWordsDetailEntity;
import com.easywecom.wecom.domain.WeWordsGroupEntity;
import com.easywecom.wecom.domain.dto.*;
import com.easywecom.wecom.domain.dto.message.TextMessageDTO;
import com.easywecom.wecom.domain.vo.FindExistWordsCategoryNameList;
import com.easywecom.wecom.domain.vo.WeWordsImportVO;
import com.easywecom.wecom.domain.vo.WeWordsUrlVO;
import com.easywecom.wecom.domain.vo.WeWordsVO;
import com.easywecom.wecom.mapper.WeWordsDetailMapper;
import com.easywecom.wecom.mapper.WeWordsGroupMapper;
import com.easywecom.wecom.service.WeCorpAccountService;
import com.easywecom.wecom.service.WeWordsCategoryService;
import com.easywecom.wecom.service.WeWordsDetailService;
import com.easywecom.wecom.service.WeWordsGroupService;
import com.easywecom.wecom.utils.JsoupUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * ????????? ?????????????????????
 *
 * @author ??????
 * @date 2021/10/27 17:56
 */
@Service("weWordsGroupService")
@Slf4j
public class WeWordsGroupServiceImpl extends ServiceImpl<WeWordsGroupMapper, WeWordsGroupEntity> implements WeWordsGroupService {
    private final WeWordsDetailService weWordsDetailService;
    private final WeWordsGroupMapper weWordsGroupMapper;
    private final WeWordsDetailMapper weWordsDetailMapper;
    private final WeWordsCategoryService weWordsCategoryService;
    private final WeMessagePushClient messagePushClient;
    private final WeCorpAccountService corpAccountService;


    @Autowired
    public WeWordsGroupServiceImpl(WeWordsDetailService weWordsDetailService, WeWordsGroupMapper weWordsGroupMapper, WeWordsDetailMapper weWordsDetailMapper, WeWordsCategoryService weWordsCategoryService, WeMessagePushClient messagePushClient, WeCorpAccountService corpAccountService) {
        this.weWordsDetailService = weWordsDetailService;
        this.weWordsGroupMapper = weWordsGroupMapper;
        this.weWordsDetailMapper = weWordsDetailMapper;
        this.weWordsCategoryService = weWordsCategoryService;
        this.messagePushClient = messagePushClient;
        this.corpAccountService = corpAccountService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(WeWordsDTO weWordsDTO) {
        StringUtils.checkCorpId(weWordsDTO.getCorpId());
        //????????????
        checkWordsDTO(weWordsDTO);
        //??????????????????
        WeWordsGroupEntity weWordsGroupEntity = new WeWordsGroupEntity();
        BeanUtils.copyProperties(weWordsDTO, weWordsGroupEntity);
        weWordsGroupEntity.setSeq(new String[]{});
        if (weWordsGroupEntity.getIsPush() == null) {
            weWordsGroupEntity.setIsPush(Boolean.TRUE);
        }
        if (weWordsGroupEntity.getTitle() == null) {
            weWordsGroupEntity.setTitle(StringUtils.EMPTY);
        }
        //??????????????????
        weWordsGroupMapper.insert(weWordsGroupEntity);
        //???????????????
        weWordsGroupEntity.setSort(weWordsGroupEntity.getId());
        List<WeWordsDetailEntity> weWordsDetailEntities = weWordsDTO.getWeWordsDetailList();
        //????????????id
        weWordsDetailEntities.forEach(weWordsDetailEntity -> {
            weWordsDetailEntity.setGroupId(weWordsGroupEntity.getId());
            weWordsDetailEntity.setCorpId(weWordsGroupEntity.getCorpId());
        });
        wordsHandler(weWordsGroupEntity, weWordsDetailEntities);
        //?????????????????????
        if (weWordsGroupEntity.getIsPush()) {
            sendToUser(weWordsDTO);
        }
    }

    @Override
    public WeWordsGroupEntity get(Integer id) {
        return weWordsGroupMapper.selectById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(WeWordsDTO weWordsDTO) {
        //????????????
        checkWordsDTO(weWordsDTO);
        WeWordsGroupEntity weWordsGroupEntity = new WeWordsGroupEntity();
        BeanUtils.copyProperties(weWordsDTO, weWordsGroupEntity);
        StringUtils.checkCorpId(weWordsGroupEntity.getCorpId());

        List<WeWordsDetailEntity> weWordsDetailEntities = weWordsDTO.getWeWordsDetailList();
        weWordsDetailEntities.forEach(weWordsDetailEntity -> {
            weWordsDetailEntity.setCorpId(weWordsGroupEntity.getCorpId());
            weWordsDetailEntity.setGroupId(weWordsDTO.getId());
        });
        wordsHandler(weWordsGroupEntity, weWordsDetailEntities);
        //?????????????????????
        if (CollectionUtils.isNotEmpty(weWordsDTO.getWordsDetailIds())) {
            weWordsDetailMapper.deleteBatchIds(weWordsDTO.getWordsDetailIds());
        }
        //?????????????????????
        if (weWordsGroupEntity.getIsPush()) {
            sendToUser(weWordsDTO);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(List<Long> ids, String corpId) {
        StringUtils.checkCorpId(corpId);
        if (CollectionUtils.isEmpty(ids)) {
            throw new CustomException(ResultTip.TIP_MISS_WORDS_GROUP_ID);
        }
        //????????????
        weWordsGroupMapper.deleteBatchIds(ids, corpId);
        //????????????
        weWordsDetailMapper.deleteByGroupIds(ids, corpId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByCategoryId(List<Long> categoryIds, String corpId) {
        StringUtils.checkCorpId(corpId);
        if (CollectionUtils.isEmpty(categoryIds)) {
            throw new CustomException(ResultTip.TIP_MISS_WORDS_CATEGORY_ID);
        }
        List<Long> groupIds = weWordsGroupMapper.listOfCategoryId(categoryIds, corpId);
        if (CollectionUtils.isNotEmpty(groupIds)) {
            delete(groupIds, corpId);
        }
    }

    @Override
    public List<WeWordsVO> listOfWords(WeWordsQueryDTO weWordsQueryDTO) {
        if (weWordsQueryDTO == null || CollectionUtils.isEmpty(weWordsQueryDTO.getCategoryIds())) {
            throw new CustomException(ResultTip.TIP_MISS_WORDS_CATEGORY_ID);
        }
        return weWordsGroupMapper.listOfWords(weWordsQueryDTO);
    }

    @Override
    public void updateCategory(Long categoryId, List<Long> ids, String corpId) {
        StringUtils.checkCorpId(corpId);
        if (categoryId == null || CollectionUtils.isEmpty(ids)) {
            throw new CustomException(ResultTip.TIP_MISS_WORDS_PARAMETER);
        }
        weWordsGroupMapper.updateCategory(categoryId, ids, corpId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WeWordsImportVO importWords(MultipartFile file, LoginUser loginUser, Integer type) {
        ExcelUtil<WeWordsImportDTO> util = new ExcelUtil<>(WeWordsImportDTO.class);
        List<WeWordsGroupEntity> weWordsGroupEntities = new ArrayList<>();
        List<WeWordsDetailEntity> weWordsDetailEntities = new ArrayList<>();
        List<WeWordsImportDTO> words = new ArrayList<>();
        List<WeWordsImportDTO> wordsImport = new ArrayList<>();
        try {
            words = util.importExcel(file.getInputStream());
        } catch (Exception e) {
            log.error("??????????????????????????????ex={}", ExceptionUtils.getStackTrace(e));
        }

        //??????????????????????????????
        WeWordsImportVO wordsImportVO = buildImportResult(words, wordsImport);
        List<String> nameList = wordsImport.stream().map(WeWordsImportDTO::getCategoryName).collect(Collectors.toList());
        //????????????????????????
        if (CollectionUtils.isEmpty(nameList)) {
            return wordsImportVO;
        }
        //??????????????????????????????????????????
        String useRange = buildUseRange(type, loginUser);
        List<FindExistWordsCategoryNameList> wordsCategory = weWordsCategoryService.findAndAddWordsCategory(loginUser.getCorpId(), type, nameList, useRange);
        Map<String, Long> wordsMap = wordsCategory.stream().collect(Collectors.toMap(FindExistWordsCategoryNameList::getName, FindExistWordsCategoryNameList::getId));
        //????????????
        buildWords(weWordsGroupEntities, weWordsDetailEntities, wordsImport, loginUser.getCorpId(), wordsMap);
        //???????????????sort
        WeWordsGroupEntity weWordsGroupEntity = weWordsGroupMapper.selectOne(new LambdaQueryWrapper<WeWordsGroupEntity>().eq(WeWordsGroupEntity::getCorpId, loginUser.getCorpId()).orderByDesc(WeWordsGroupEntity::getSort).last(GenConstants.LIMIT_1));
        Long sort = weWordsGroupEntity == null ? 0 : weWordsGroupEntity.getSort();
        for (WeWordsGroupEntity weWordsEntity : weWordsGroupEntities) {
            sort++;
            weWordsEntity.setSort(sort);
        }
        //????????????????????????
        weWordsGroupMapper.batchInsert(weWordsGroupEntities);

        //????????????id
        for (int i = 0; i < weWordsDetailEntities.size(); i++) {
            weWordsDetailEntities.get(i).setGroupId(weWordsGroupEntities.get(i).getId());
        }
        //????????????
        weWordsDetailService.saveOrUpdate(weWordsDetailEntities);
        //????????????seq??????
        for (int i = 0; i < weWordsGroupEntities.size(); i++) {
            weWordsGroupEntities.get(i).setSeq(ArrayUtils.toArray(weWordsDetailEntities.get(i).getId().toString()));
        }
        weWordsGroupMapper.batchUpdateSeq(weWordsGroupEntities, loginUser.getCorpId());
        return wordsImportVO;
    }

    @Override
    public WeWordsUrlVO matchUrl(String address){
        return JsoupUtil.matchUrl(address);
    }

    /**
     * ??????????????????????????????
     *
     * @param words       ??????
     * @param wordsImport ??????????????????
     * @return {@link WeWordsImportVO}
     */
    private WeWordsImportVO buildImportResult(List<WeWordsImportDTO> words, List<WeWordsImportDTO> wordsImport) {
        final String overSize = "????????????,??????????????????1000?????????";
        final String contentEmpty = "????????????????????????";
        final int contentSize = 1500;
        final int titleSize = 64;
        final int categorySize = 12;
        final int wordsSize = 1000;
        StringBuilder failMsg = new StringBuilder();
        WeWordsImportVO wordsImportVO = new WeWordsImportVO();
        //????????????
        int emptyCount = 0;
        if (words.size() > wordsSize) {
            failMsg.append(overSize);
        }
        for (int i = 0; i < words.size(); i++) {
            WeWordsImportDTO weWordsImportDTO = words.get(i);
            // ??????
            if (StringUtils.isBlank(weWordsImportDTO.getCategoryName()) || StringUtils.isBlank(weWordsImportDTO.getContent())) {
                if (StringUtils.isBlank(weWordsImportDTO.getTitle())) {
                    emptyCount++;
                    continue;
                }
                failMsg.append("??? ").append(i + 2).append(" ???,").append(contentEmpty).append("\r\n");
                continue;
            }
            //??????????????????
            if (weWordsImportDTO.getCategoryName().length() > categorySize) {
                weWordsImportDTO.setCategoryName(weWordsImportDTO.getCategoryName().substring(0, categorySize));
            }
            if (weWordsImportDTO.getTitle().length() > titleSize) {
                weWordsImportDTO.setTitle(weWordsImportDTO.getTitle().substring(0, titleSize));
            }
            if (weWordsImportDTO.getContent().length() > contentSize) {
                weWordsImportDTO.setContent(weWordsImportDTO.getContent().substring(0, contentSize));
            }
            wordsImport.add(weWordsImportDTO);
        }

        wordsImportVO.setSuccessNum(wordsImport.size());
        wordsImportVO.setFailNum(words.size() - wordsImport.size() - emptyCount);
        if (wordsImportVO.getFailNum() > 0) {
            String suffix = "txt";
            String fileName = System.currentTimeMillis() + new Random().nextInt(wordsSize) + ".txt";
            RuoYiConfig ruoyiConfig = SpringUtils.getBean(RuoYiConfig.class);
            CosConfig cosConfig = ruoyiConfig.getFile().getCos();
            try {
                String url = FileUploadUtils.upload2Cos(new ByteArrayInputStream(failMsg.toString().getBytes(StandardCharsets.UTF_8)), fileName, suffix, cosConfig);
                String imgUrlPrefix = ruoyiConfig.getFile().getCos().getCosImgUrlPrefix();
                wordsImportVO.setUrl(imgUrlPrefix + url);
            } catch (IOException e) {
                log.error("??????????????????????????????????????????ex:{}", ExceptionUtils.getStackTrace(e));
            }
        }
        return wordsImportVO;
    }


    /**
     * ???????????????????????????????????????
     *
     * @param words ??????????????????
     */
    private void buildWords(List<WeWordsGroupEntity> weWordsGroupEntities, List<WeWordsDetailEntity> weWordsDetailEntities, List<WeWordsImportDTO> words, String corpId, Map<String, Long> wordsMap) {

        words.forEach(weWordsImportDTO -> {
            WeWordsGroupEntity weWordsGroupEntity = new WeWordsGroupEntity(weWordsImportDTO.getTitle(), corpId, wordsMap.get(weWordsImportDTO.getCategoryName()));
            weWordsGroupEntities.add(weWordsGroupEntity);
            WeWordsDetailEntity weWordsDetailEntity = new WeWordsDetailEntity(corpId, WeConstans.WE_WORDS_DETAIL_MEDIATYPE_TEXT, weWordsImportDTO.getContent());
            weWordsDetailEntities.add(weWordsDetailEntity);
        });
    }

    /**
     * ????????????????????????
     *
     * @param type      ??????
     * @param loginUser ????????????
     * @return ?????????????????????????????????????????????1???????????????????????????????????????????????????id???
     */
    private String buildUseRange(Integer type, LoginUser loginUser) {
        if (WeWordsCategoryTypeEnum.CORP.getType().equals(type)) {
            return WeConstans.ROOT_DEPARTMENT;
        } else if (WeWordsCategoryTypeEnum.DEPARTMENT.getType().equals(type)) {
            return loginUser.getWeUser().getMainDepartment().toString();
        } else {
            return loginUser.getWeUser().getUserId();
        }
    }

    /**
     * ??????????????????????????????
     *
     * @param weWordsGroupEntity    ??????
     * @param weWordsDetailEntities ??????
     */
    private void wordsHandler(WeWordsGroupEntity weWordsGroupEntity, List<WeWordsDetailEntity> weWordsDetailEntities) {
        //?????????????????????
        weWordsDetailService.saveOrUpdate(weWordsDetailEntities);
        //?????????????????????id
        List<String> ids = weWordsDetailEntities.stream().map(weWordsDetailEntity -> weWordsDetailEntity.getId().toString()).collect(Collectors.toList());
        weWordsGroupEntity.setSeq(ids.toArray(new String[]{}));

        //????????????
        weWordsGroupMapper.update(weWordsGroupEntity);
    }

    /**
     * ????????????
     *
     * @param weWordsDTO ??????
     */
    private void checkWordsDTO(WeWordsDTO weWordsDTO) {
        //????????????
        if (weWordsDTO == null || CollectionUtils.isEmpty(weWordsDTO.getWeWordsDetailList())) {
            throw new CustomException(ResultTip.TIP_MISS_WORDS_PARAMETER);
        }
    }

    /**
     * ???????????????????????????
     *
     * @param weWordsDTO ????????????
     */
    private void sendToUser(WeWordsDTO weWordsDTO) {
        WeMessagePushDTO pushDto = new WeMessagePushDTO();
        WeWordsCategory wordsCategory = weWordsCategoryService.getById(weWordsDTO.getCategoryId());
        WeCorpAccount validWeCorpAccount = corpAccountService.findValidWeCorpAccount(weWordsDTO.getCorpId());
        String agentId = validWeCorpAccount.getAgentId();
        // ????????????
        TextMessageDTO text = new TextMessageDTO();
        StringBuilder content = new StringBuilder();
        //??????????????? ???????????????????????????
        if (WeWordsCategoryTypeEnum.CORP.getType().equals(wordsCategory.getType())) {
            pushDto.setTouser("@all");
            content.append("????????????????????????");
            if (StringUtils.isNotBlank(weWordsDTO.getTitle())) {
                content.append("???").append(weWordsDTO.getTitle()).append("???");
            }
            text.setContent(content.toString());
        } else if (WeWordsCategoryTypeEnum.DEPARTMENT.getType().equals(wordsCategory.getType())) {
            //??????????????? ???????????????????????????
            pushDto.setToparty(weWordsDTO.getMainDepartment().toString());
            content.append("????????????????????????");
            if (StringUtils.isNotBlank(weWordsDTO.getTitle())) {
                content.append("???").append(weWordsDTO.getTitle()).append("???");
            }
            text.setContent(content.toString());
        } else {
            //?????????????????????????????????????????????
            return;
        }
        pushDto.setAgentid(Integer.valueOf(agentId));
        pushDto.setText(text);
        pushDto.setMsgtype(MessageType.TEXT.getMessageType());
        // ??????????????????????????????????????? [???????????? - ??????????????????]
        log.debug("???????????????????????????toUser:{},toParty:{}", pushDto.getTouser(), pushDto.getToparty());
        messagePushClient.sendMessageToUser(pushDto, agentId, weWordsDTO.getCorpId());
    }


    @Override
    public int changeSort(WeWordsSortDTO sortDTO) {
        if (sortDTO == null || CollectionUtils.isEmpty(sortDTO.getWordsChangeSortDTOList())) {
            throw new CustomException(ResultTip.TIP_MISS_WORDS_SORT_INFO);
        }
        StringUtils.checkCorpId(sortDTO.getCorpId());
        return weWordsGroupMapper.changeSort(sortDTO.getCorpId(), sortDTO.getWordsChangeSortDTOList());
    }

}