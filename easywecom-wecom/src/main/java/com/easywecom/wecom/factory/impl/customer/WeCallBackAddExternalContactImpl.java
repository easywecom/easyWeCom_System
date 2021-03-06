package com.easywecom.wecom.factory.impl.customer;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.ObjectUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.easywecom.common.config.RuoYiConfig;
import com.easywecom.common.constant.Constants;
import com.easywecom.common.constant.GenConstants;
import com.easywecom.common.constant.WeConstans;
import com.easywecom.common.core.domain.entity.WeCorpAccount;
import com.easywecom.common.core.redis.RedisCache;
import com.easywecom.common.enums.*;
import com.easywecom.common.enums.code.WelcomeMsgTypeEnum;
import com.easywecom.common.exception.CustomException;
import com.easywecom.common.utils.ExceptionUtil;
import com.easywecom.common.utils.StringUtils;
import com.easywecom.wecom.client.WeMessagePushClient;
import com.easywecom.wecom.domain.*;
import com.easywecom.wecom.domain.dto.AddWeMaterialDTO;
import com.easywecom.wecom.domain.dto.WeMediaDTO;
import com.easywecom.wecom.domain.dto.WeMessagePushDTO;
import com.easywecom.wecom.domain.dto.WeWelcomeMsg;
import com.easywecom.wecom.domain.dto.common.Attachment;
import com.easywecom.wecom.domain.dto.message.TextMessageDTO;
import com.easywecom.wecom.domain.dto.redeemcode.WeRedeemCodeDTO;
import com.easywecom.wecom.domain.vo.redeemcode.WeRedeemCodeActivityVO;
import com.easywecom.wecom.domain.vo.welcomemsg.WeEmployMaterialVO;
import com.easywecom.wecom.domain.dto.common.*;
import com.easywecom.wecom.domain.vo.SelectWeEmplyCodeWelcomeMsgVO;
import com.easywecom.wecom.domain.vo.WeMakeCustomerTagVO;
import com.easywecom.wecom.domain.vo.WxCpXmlMessageVO;
import com.easywecom.wecom.factory.WeEventStrategy;
import com.easywecom.wecom.mapper.WeMaterialMapper;
import com.easywecom.wecom.mapper.redeemcode.WeRedeemCodeMapper;
import com.easywecom.wecom.service.*;
import com.easywecom.wecom.service.autotag.WeAutoTagRuleHitCustomerRecordService;
import com.easywecom.wecom.service.redeemcode.WeRedeemCodeActivityService;
import com.easywecom.wecom.service.redeemcode.WeRedeemCodeService;
import com.easywecom.wecom.utils.AttachmentService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * @author admin
 * @description ??????????????????
 * @date 2021/1/20 23:18
 **/
@Slf4j
@Component("add_external_contact")
public class WeCallBackAddExternalContactImpl extends WeEventStrategy {
    @Autowired
    private WeCustomerService weCustomerService;
    @Autowired
    private WeEmpleCodeTagService weEmpleCodeTagService;
    @Autowired
    private WeEmpleCodeService weEmpleCodeService;
    @Autowired
    private WeFlowerCustomerRelService weFlowerCustomerRelService;
    @Autowired
    private WeMaterialService weMaterialService;
    @Autowired
    private RuoYiConfig ruoYiConfig;
    @Autowired
    private WeEmpleCodeAnalyseService weEmpleCodeAnalyseService;

    @Autowired
    private WeTagService weTagService;
    @Autowired
    private RedisCache redisCache;

    @Autowired
    private WeCustomerTrajectoryService weCustomerTrajectoryService;
    @Autowired
    private WeMsgTlpService weMsgTlpService;
    @Autowired
    private WeMsgTlpMaterialService weMsgTlpMaterialService;
    @Autowired
    private AttachmentService attachmentService;
    @Autowired
    private WeAutoTagRuleHitCustomerRecordService weAutoTagRuleHitCustomerRecordService;
    @Autowired
    private WeMaterialMapper weMaterialMapper;
    @Autowired
    private WeRedeemCodeService weRedeemCodeService;
    @Autowired
    private WeRedeemCodeActivityService weRedeemCodeActivityService;
    private final WeCorpAccountService corpAccountService;
    private final WeMessagePushClient messagePushClient;
    private final WeRedeemCodeMapper weRedeemCodeMapper;
    private final WeUserService weUserService;

    @Autowired
    public WeCallBackAddExternalContactImpl(WeCorpAccountService corpAccountService, WeMessagePushClient messagePushClient, WeRedeemCodeMapper weRedeemCodeMapper, WeUserService weUserService) {
        this.corpAccountService = corpAccountService;
        this.messagePushClient = messagePushClient;
        this.weRedeemCodeMapper = weRedeemCodeMapper;
        this.weUserService = weUserService;
    }


    @Override
    public void eventHandle(WxCpXmlMessageVO message) {

        if (!checkParams(message)) {
            return;
        }
        String corpId = message.getToUserName();

        // ????????????????????????
        try {
            weCustomerService.updateExternalContactV2(corpId, message.getUserId(), message.getExternalUserId());
        } catch (Exception e) {
            log.error("[{}]:????????????,????????????????????????,message:{},e:{}", message.getChangeType(), message, ExceptionUtils.getStackTrace(e));
        }
        // ???????????????
        if (message.getState() != null && message.getWelcomeCode() != null && !isFission(message.getState())) {
            // ?????????????????????
            empleCodeHandle(message.getState(), message.getWelcomeCode(), message.getUserId(), message.getExternalUserId(), corpId);
        } else if (message.getWelcomeCode() != null) {
            // ????????????????????????
            otherHandle(message.getWelcomeCode(), message.getUserId(), message.getExternalUserId(), corpId);
        } else {
            log.error("[{}]:????????????,?????????????????????,message:{}", message.getChangeType(), message);
        }
        weAutoTagRuleHitCustomerRecordService.makeTagToNewCustomer(message.getExternalUserId(), message.getUserId(), corpId);

        // ?????????????????? : ????????????
        weCustomerTrajectoryService.saveActivityRecord(corpId, message.getUserId(), message.getExternalUserId(),
                CustomerTrajectoryEnums.SubType.ADD_USER.getType());
    }

    private boolean checkParams(WxCpXmlMessageVO message) {
        if (message == null || StringUtils.isAnyBlank(message.getToUserName(), message.getUserId(), message.getExternalUserId())) {
            log.error("[add_external_contact]:?????????????????????,message:{}", message);
            return false;
        }
        if (!redisCache.addLock(message.getUniqueKey(message.getExternalUserId()), "", Constants.CALLBACK_HANDLE_LOCK_TIME)) {
            log.info("[{}]????????????????????????,??????????????????,???????????????,message:{}", message.getChangeType(), message);
            // ???????????????
            return false;
        }
        return true;
    }

    /**
     * ?????????????????????
     *
     * @param welcomeCode    ?????????code
     * @param userId         ??????id
     * @param externalUserId ??????id
     * @param corpId         ??????id
     */
    private void otherHandle(String welcomeCode, String userId, String externalUserId, String corpId) {
        log.info("???????????????????????????????????????otherHandle>>>>>>>>>>>>>>>");
        WeWelcomeMsg.WeWelcomeMsgBuilder weWelcomeMsgBuilder = WeWelcomeMsg.builder().welcome_code(welcomeCode);
        //?????????????????????????????????????????????
        WeFlowerCustomerRel weFlowerCustomerRel = weFlowerCustomerRelService
                .getOne(new LambdaQueryWrapper<WeFlowerCustomerRel>()
                        .eq(WeFlowerCustomerRel::getUserId, userId)
                        .eq(WeFlowerCustomerRel::getExternalUserid, externalUserId)
                        .eq(WeFlowerCustomerRel::getStatus, CustomerStatusEnum.NORMAL.getCode())
                        .eq(WeFlowerCustomerRel::getCorpId, corpId));

        CompletableFuture.runAsync(() -> {
            try {
                WeEmployMaterialVO weEmployMaterialVO = weMsgTlpService.selectMaterialByUserId(userId, corpId);
                // ?????????????????????????????????
                if (weEmployMaterialVO != null
                        && (CollectionUtils.isNotEmpty(weEmployMaterialVO.getWeMsgTlpMaterialList()) || StringUtils.isNotEmpty(weEmployMaterialVO.getDefaultMsg()))) {
                    // ????????????????????????
                    buildAndSendOtherWelcomeMsg(weEmployMaterialVO, userId, externalUserId, corpId, weWelcomeMsgBuilder, weFlowerCustomerRel.getRemark());
                    log.info("???????????????????????????>>>>>>>>>>>>>>>");
                }
            } catch (Exception e) {
                log.error("????????????????????????????????????ex:{}", ExceptionUtil.getExceptionMessage(e));
            }
        });
    }

    /**
     * ?????????????????????
     */
    private void buildAndSendOtherWelcomeMsg(WeEmployMaterialVO weEmployMaterialVO, String userId, String externalUserId, String corpId, WeWelcomeMsg.WeWelcomeMsgBuilder weWelcomeMsgBuilder, String remark) {
        // ????????????
        WeWelcomeMsg weWelcomeMsg = weMsgTlpMaterialService.buildWeWelcomeMsg(weEmployMaterialVO.getDefaultMsg(), weEmployMaterialVO.getWeMsgTlpMaterialList(), weWelcomeMsgBuilder, userId, externalUserId, corpId, remark);
        // ?????????????????????????????????????????????
        weCustomerService.sendWelcomeMsg(weWelcomeMsg, corpId);
    }


    /**
     * ?????????????????????
     *
     * @param state          ??????
     * @param welcomeCode    ?????????code
     * @param userId         ??????id
     * @param externalUserId ??????id
     */
    private void empleCodeHandle(String state, String welcomeCode, String userId, String externalUserId, String corpId) {
        log.info("???????????????????????????empleCodeHandle>>>>>>>>>>>>>>>");
        try {
            WeWelcomeMsg.WeWelcomeMsgBuilder weWelcomeMsgBuilder = WeWelcomeMsg.builder().welcome_code(welcomeCode);
            SelectWeEmplyCodeWelcomeMsgVO messageMap = weEmpleCodeService.selectWelcomeMsgByState(state, corpId, externalUserId);

            if (StringUtils.isNotNull(messageMap) && org.apache.commons.lang3.StringUtils.isNotBlank(messageMap.getEmpleCodeId())) {
                String empleCodeId = messageMap.getEmpleCodeId();
                //????????????????????????
                weEmpleCodeAnalyseService.saveWeEmpleCodeAnalyse(corpId, userId, externalUserId, empleCodeId, true);

                //?????????????????????????????????????????????
                WeFlowerCustomerRel weFlowerCustomerRel = weFlowerCustomerRelService
                        .getOne(new LambdaQueryWrapper<WeFlowerCustomerRel>()
                                .eq(WeFlowerCustomerRel::getUserId, userId)
                                .eq(WeFlowerCustomerRel::getExternalUserid, externalUserId)
                                .eq(WeFlowerCustomerRel::getStatus, 0).last(GenConstants.LIMIT_1)
                        );
                //????????????????????????
                weFlowerCustomerRel.setState(state);
                weFlowerCustomerRelService.updateById(weFlowerCustomerRel);

                //?????????????????????
                CompletableFuture.runAsync(() -> {
                    try {
                        sendMessageToNewExternalUserId(weWelcomeMsgBuilder, messageMap, weFlowerCustomerRel.getRemark(), corpId, userId, externalUserId, state);
                    } catch (Exception e) {
                        log.error("????????????????????????????????????ex:{}", ExceptionUtil.getExceptionMessage(e));
                    }
                });

                //??????????????????????????????????????????
                setEmplyCodeTag(weFlowerCustomerRel, empleCodeId, messageMap.getTagFlag());

                //??????????????????????????????
                setEmplyCodeExternalUserRemark(state, userId, externalUserId, corpId, messageMap.getRemarkType(), messageMap.getRemarkName(), weFlowerCustomerRel.getRemark());
            }
        } catch (Exception e) {
            log.error("empleCodeHandle error!! e={}", ExceptionUtils.getStackTrace(e));
        }
    }


    /**
     * ??????????????????????????????????????????
     *
     * @param state          ??????state
     * @param userId         ??????userId
     * @param externalUserId ??????
     * @param corpId         ??????ID
     * @param remarkType     ????????????
     * @param remarkName     ??????????????????
     * @param nickName       ????????????
     */
    private void setEmplyCodeExternalUserRemark(String state, String userId, String externalUserId, String corpId,
                                                Integer remarkType, String remarkName, String nickName) {
        if (org.apache.commons.lang3.StringUtils.isBlank(userId)
                || org.apache.commons.lang3.StringUtils.isBlank(externalUserId)
                || org.apache.commons.lang3.StringUtils.isBlank(corpId)
                || remarkType == null) {
            log.error("setEmplyCodeExternalUserRemark param error!! state={},userId={},externalUserId={},corpId={},remarkType={}", state, userId, externalUserId, corpId, remarkType);
            return;
        }
        if (WeEmployCodeRemarkTypeEnum.NO.getRemarkType().equals(remarkType) || org.apache.commons.lang3.StringUtils.isBlank(remarkName)) {
            log.info("setEmplyCodeExternalUserRemark. remarkType={},remarkName={}", remarkType, remarkName);
            return;
        }
        String newRemark;
        log.info("setEmplyCodeExternalUserRemark ???????????? ??????????????????????????????");
        if (WeEmployCodeRemarkTypeEnum.BEFORT_NICKNAME.getRemarkType().equals(remarkType)) {
            newRemark = remarkName + "-" + nickName;
        } else {
            newRemark = nickName + "-" + remarkName;
        }
        WeCustomer weCustomer = new WeCustomer();
        weCustomer.setCorpId(corpId);
        weCustomer.setUserId(userId);
        weCustomer.setExternalUserid(externalUserId);
        weCustomer.setRemark(newRemark);
        try {
            weCustomerService.updateWeCustomerRemark(weCustomer);
        } catch (Exception e) {
            log.error("setEmplyCodeExternalUserRemark error!! corpId={},userId={},externalUserId={},", corpId, userId, externalUserId);
        }
    }


    /**
     * ????????????????????????
     *
     * @param weFlowerCustomerRel weFlowerCustomerRel
     * @param empleCodeId         ??????????????????ID
     * @param tagFlag             ???????????????
     */
    private void setEmplyCodeTag(WeFlowerCustomerRel weFlowerCustomerRel, String empleCodeId, Boolean tagFlag) {
        if (weFlowerCustomerRel == null || org.apache.commons.lang3.StringUtils.isBlank(empleCodeId) || tagFlag == null) {
            log.warn("setEmplyCodeTag warn!! empleCodeId={},tagFlag={},weFlowerCustomerRel={}", empleCodeId, tagFlag, JSONObject.toJSONString(weFlowerCustomerRel));
            return;
        }
        if (!tagFlag) {
            log.info("setEmplyCodeTag. empleCodeId={} ??????????????????");
            return;
        }
        try {
            //????????????????????????
            List<WeEmpleCodeTag> tagList = weEmpleCodeTagService.list(new LambdaQueryWrapper<WeEmpleCodeTag>().eq(WeEmpleCodeTag::getEmpleCodeId, empleCodeId));

            //??????????????????
            if (CollectionUtils.isNotEmpty(tagList)) {
                log.info("setEmplyCodeTag ???????????? ????????????????????????");
                //????????????tagId?????????groupId
                List<String> tagIdList = tagList.stream().map(WeEmpleCodeTag::getTagId).collect(Collectors.toList());
                List<WeTag> weTagList = weTagService.list(new LambdaQueryWrapper<WeTag>().in(WeTag::getTagId, tagIdList));
                if (CollectionUtils.isEmpty(weTagList)) {
                    log.warn("?????????tagId?????????groupId!! tagIdList={}", JSONObject.toJSONString(tagIdList));
                    return;
                }

                WeMakeCustomerTagVO weMakeCustomerTag = new WeMakeCustomerTagVO();
                weMakeCustomerTag.setUserId(weFlowerCustomerRel.getUserId());
                weMakeCustomerTag.setExternalUserid(weFlowerCustomerRel.getExternalUserid());
                weMakeCustomerTag.setAddTag(weTagList);
                weMakeCustomerTag.setCorpId(weFlowerCustomerRel.getCorpId());

                List<WeMakeCustomerTagVO> weMakeCustomerTagList = new ArrayList<>();
                weMakeCustomerTagList.add(weMakeCustomerTag);
                weCustomerService.makeLabelbatch(weMakeCustomerTagList);
            }
        } catch (Exception e) {
            log.error("setEmplyCodeTag error!! empleCodeId={},e={}", empleCodeId, ExceptionUtils.getStackTrace(e));
        }
    }

    /**
     * ?????????????????????
     */
    private void sendMessageToNewExternalUserId(WeWelcomeMsg.WeWelcomeMsgBuilder weWelcomeMsgBuilder,
                                                SelectWeEmplyCodeWelcomeMsgVO messageMap, String remark,
                                                String corpId, String userId, String externalUserId, String state) {
        log.debug(">>>>>>>>>????????????????????????{}", JSON.toJSONString(messageMap));
        // 1.???????????????
        // ????????????????????????????????????????????????
        String replyText = weMsgTlpMaterialService.replyTextIfNecessary(messageMap.getWelcomeMsg(), remark, messageMap.getRedeemCode(), externalUserId, userId, corpId);
        Optional.ofNullable(replyText).ifPresent(text -> weWelcomeMsgBuilder.text(Text.builder().content(text).build()));

        // 2.????????????
        List<Attachment> attachmentList = new ArrayList<>();
        // 2.1 ??????????????????????????????
        if (EmployCodeSourceEnum.NEW_GROUP.getSource().equals(messageMap.getSource())) {
            // ????????????????????????????????????????????????(???????????????)
            String codeUrl = messageMap.getGroupCodeUrl();
            if (StringUtils.isNotNull(codeUrl)) {
                String cosImgUrlPrefix = ruoYiConfig.getFile().getCos().getCosImgUrlPrefix();
                buildWelcomeMsgImg(corpId, codeUrl, codeUrl.replaceAll(cosImgUrlPrefix, ""), attachmentList);
            }
        }
        // ?????????????????????
        if (CollectionUtils.isNotEmpty(messageMap.getMaterialList())) {
            //???????????????????????????
            if (messageMap.getMaterialList().size() > WeConstans.MAX_ATTACHMENT_NUM) {
                throw new CustomException(ResultTip.TIP_ATTACHMENT_OVER);
            }
            buildWeEmplyWelcomeMsg(state, corpId, weWelcomeMsgBuilder, messageMap.getMaterialList(), attachmentList);
        }

        // 3.???????????????????????????????????????
        weCustomerService.sendWelcomeMsg(weWelcomeMsgBuilder.attachments(attachmentList).build(), corpId);

        if (WelcomeMsgTypeEnum.REDEEM_CODE_WELCOME_MSG_TYPE.getType().equals(messageMap.getWelcomeMsgType())) {
            // 4.??????????????????????????????
            if (StringUtils.isNotBlank(messageMap.getRedeemCode())) {
                weRedeemCodeService.updateRedeemCode(WeRedeemCodeDTO.builder()
                        .activityId(Long.valueOf(messageMap.getCodeActivityId()))
                        .code(messageMap.getRedeemCode())
                        .corpId(corpId)
                        .receiveUserId(externalUserId).build());
            }
        }
    }

    private boolean isFission(String str) {
        return str.contains(WeConstans.FISSION_PREFIX);
    }

    /**
     * ??????????????????????????????
     *
     * @param picUrl         ????????????
     * @param fileName       ????????????
     * @param attachmentList
     */
    private void buildWelcomeMsgImg(String corpId, String picUrl, String fileName, List<Attachment> attachmentList) {

        AttachmentParam param = AttachmentParam.builder().picUrl(picUrl).typeEnum(AttachmentTypeEnum.IMAGE).build();
        Attachments attachments = attachmentService.buildAttachment(param, corpId);
        if (attachments != null) {
            attachmentList.add(attachments);
        }
//        Optional.ofNullable(weMediaDto).ifPresent(media -> builder.image(Image.builder().media_id(media.getMedia_id()).pic_url(media.getUrl()).build()));
    }


    private void buildWeEmplyWelcomeMsg(String state, String corpId, WeWelcomeMsg.WeWelcomeMsgBuilder builder, List<AddWeMaterialDTO> weMaterialList, List<Attachment> attachmentList) {
        if (StringUtils.isBlank(corpId) || CollectionUtils.isEmpty(weMaterialList) || builder == null) {
            throw new CustomException(ResultTip.TIP_MISS_CORP_ID);
        }
        Attachments attachments;
        for (AddWeMaterialDTO weMaterialVO : weMaterialList) {
            AttachmentTypeEnum typeEnum = AttachmentTypeEnum.mappingFromGroupMessageType(weMaterialVO.getMediaType());
            if (typeEnum == null) {
                log.error("type is error!!!, type: {}", weMaterialVO.getMediaType());
                continue;
            }
            AttachmentParam param = AttachmentParam.costFromWeMaterialByType(weMaterialVO, typeEnum);
            attachments = attachmentService.buildAttachment(param, corpId);
//            attachments = weMsgTlpMaterialService.buildByWelcomeMsgType(param.getContent(), param.getPicUrl(), param.getDescription(), param.getUrl(), typeEnum, corpId);
            if (attachments != null) {
                attachmentList.add(attachments);
            } else {
                log.error("type error!! state={}, mediaType={}", state, weMaterialVO.getMediaType());
            }
        }
    }

    /**
     * ????????????????????????10M,??????????????????????????????
     *
     * @param corpId        ??????ID
     * @param weMaterialDTO ????????????
     */
    private Attachment sendWelcomeMsgVideoOrLink(String corpId, AddWeMaterialDTO weMaterialDTO) {
        if (weMaterialDTO == null) {
            log.error("sendWelcomeMsgVideoOrLink param error!!");
            return null;
        }
        String content = weMaterialDTO.getContent();
        //???content??????,???????????????,??????????????????????????????
        if (org.apache.commons.lang3.StringUtils.isBlank(content)) {
            log.error("sendWelcomeMsgVideoOrLink content is null!!");
            return sendVideo(corpId, weMaterialDTO);
        }
        long videoSize = Long.parseLong(content);
        //???????????????
        if (videoSize > WeConstans.DEFAULT_MAX_VIDEO_SIZE) {
            return sendLink(weMaterialDTO);
        } else {
            //?????????
            return sendVideo(corpId, weMaterialDTO);
        }
    }

    /**
     * ????????????
     *
     * @param weMaterialDTO ????????????
     * @return JSONObject
     */
    private Attachment sendLink(AddWeMaterialDTO weMaterialDTO) {
        Attachments attachments = new Attachments();
        attachments.setMsgtype(AttachmentTypeEnum.LINK.getTypeStr());
        attachments.setLink(Link.builder()
                .title(weMaterialDTO.getMaterialName())
                .picurl(WeConstans.DEFAULT_VIDEO_COVER_URL)
                .desc(WeConstans.CLICK_SEE_VIDEO)
                .url(weMaterialDTO.getMaterialUrl())
                .build());

        return attachments;
    }


    /**
     * ??????????????????
     *
     * @param corpId        ??????ID
     * @param weMaterialDTO ????????????
     * @return JSONObject
     */
    private Attachment sendVideo(String corpId, AddWeMaterialDTO weMaterialDTO) {
        Attachments attachments = new Attachments();
        WeMediaDTO weMediaDto = weMaterialService.uploadTemporaryMaterial(weMaterialDTO.getMaterialUrl(), GroupMessageType.VIDEO.getMessageType(), FileUtil.getName(weMaterialDTO.getMaterialUrl()), corpId);
        attachments.setMsgtype(AttachmentTypeEnum.VIDEO.getTypeStr());
        attachments.setVideo(Video.builder().media_id(weMediaDto.getMedia_id()).build());
        return attachments;
    }
}
