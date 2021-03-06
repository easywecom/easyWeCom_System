package com.easywecom.wecom.service.impl.redeemcode;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.easywecom.common.config.CosConfig;
import com.easywecom.common.config.RuoYiConfig;
import com.easywecom.common.constant.WeConstans;
import com.easywecom.common.core.domain.entity.WeCorpAccount;
import com.easywecom.common.core.page.PageDomain;
import com.easywecom.common.core.page.TableSupport;
import com.easywecom.common.enums.MessageType;
import com.easywecom.common.enums.ResultTip;
import com.easywecom.common.exception.CustomException;
import com.easywecom.common.utils.DateUtils;
import com.easywecom.common.utils.file.FileUploadUtils;
import com.easywecom.common.utils.spring.SpringUtils;
import com.easywecom.common.utils.sql.SqlUtil;
import com.easywecom.wecom.client.WeMessagePushClient;
import com.easywecom.wecom.domain.dto.WeMessagePushDTO;
import com.easywecom.wecom.domain.dto.message.TextMessageDTO;
import com.easywecom.wecom.domain.dto.redeemcode.WeRedeemCodeDTO;
import com.easywecom.wecom.domain.dto.redeemcode.WeRedeemCodeDeleteDTO;
import com.easywecom.wecom.domain.dto.redeemcode.WeRedeemCodeImportDTO;
import com.easywecom.wecom.domain.entity.redeemcode.WeRedeemCode;
import com.easywecom.wecom.domain.vo.customer.WeCustomerVO;
import com.easywecom.wecom.domain.vo.redeemcode.ImportRedeemCodeVO;
import com.easywecom.wecom.domain.vo.redeemcode.WeRedeemCodeActivityVO;
import com.easywecom.wecom.domain.vo.redeemcode.WeRedeemCodeVO;
import com.easywecom.wecom.login.util.LoginTokenService;
import com.easywecom.wecom.mapper.redeemcode.WeRedeemCodeMapper;
import com.easywecom.wecom.service.WeCorpAccountService;
import com.easywecom.wecom.service.WeCustomerService;
import com.easywecom.wecom.service.redeemcode.WeRedeemCodeActivityService;
import com.easywecom.wecom.service.redeemcode.WeRedeemCodeService;
import com.github.pagehelper.PageHelper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.time.DateFormatUtils;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import static com.easywecom.common.annotation.Excel.ColumnType.STRING;
import static com.easywecom.common.utils.file.MimeTypeUtils.XLS;
import static com.easywecom.common.utils.file.MimeTypeUtils.XLSX;

/**
 * ClassName??? WeRedeemCodeServiceImpl
 *
 * @author wx
 * @date 2022/7/5 17:20
 */

@Slf4j
@Service
public class WeRedeemCodeServiceImpl extends ServiceImpl<WeRedeemCodeMapper, WeRedeemCode> implements WeRedeemCodeService {


    private final WeCustomerService weCustomerService;
    private final WeRedeemCodeMapper weRedeemCodeMapper;
    private final WeRedeemCodeActivityService weRedeemCodeActivityService;
    private final WeCorpAccountService corpAccountService;
    private final WeMessagePushClient messagePushClient;

    @Autowired
    public WeRedeemCodeServiceImpl(WeCustomerService weCustomerService, WeRedeemCodeMapper weRedeemCodeMapper, WeRedeemCodeActivityService weRedeemCodeActivityService, WeCorpAccountService corpAccountService, WeMessagePushClient messagePushClient) {
        this.weCustomerService = weCustomerService;
        this.weRedeemCodeMapper = weRedeemCodeMapper;
        this.weRedeemCodeActivityService = weRedeemCodeActivityService;
        this.corpAccountService = corpAccountService;
        this.messagePushClient = messagePushClient;
    }

    @Override
    public ImportRedeemCodeVO importRedeemCode(String corpId, MultipartFile file, String id) throws IOException {
        if (file.isEmpty()) {
            throw new CustomException(ResultTip.TIP_REDEEM_CODE_EMPTY_FILE);
        }
        String fileName = file.getOriginalFilename();
        String suffix = fileName.substring(fileName.lastIndexOf(".") + 1);
        if (!(suffix.equals(XLSX) || suffix.equals(XLS))) {
            throw new CustomException(ResultTip.TIP_REDEEM_CODE_INPUT_EXCEL);
        }
        if (StringUtils.isBlank(id)) {
            throw new CustomException(ResultTip.TIP_REDEEM_CODE_ACTIVITY_ID_IS_EMPTY);
        }
        InputStream inputStream = file.getInputStream();
        XSSFWorkbook Workbook = new XSSFWorkbook(inputStream);
        List<WeRedeemCodeImportDTO> redeemCodeImport = new ArrayList<>();
        List<WeRedeemCode> weRedeemCodeList = this.baseMapper.listWeRedeemCode(id);
        ImportRedeemCodeVO importRedeemCodeVO = buildImportResult(Workbook, redeemCodeImport, weRedeemCodeList, id);

        if (CollectionUtils.isNotEmpty(redeemCodeImport)) {
            this.baseMapper.batchInsert(redeemCodeImport);
        }
        return importRedeemCodeVO;
    }

    /**
     * ???????????????Excel
     *
     * @param workbook         ?????????Excel
     * @param redeemCodeImport ?????????????????????????????????
     * @param weRedeemCodeList ??????????????????????????????
     * @return
     */
    private ImportRedeemCodeVO buildImportResult(XSSFWorkbook workbook, List<WeRedeemCodeImportDTO> redeemCodeImport, List<WeRedeemCode> weRedeemCodeList, String activityId) {
        final String overSize = "????????????,??????????????????1000????????????";
        final String repeatCode = "?????????????????????, ???????????????????????????, ?????????????????????";
        final String contentEmpty = "?????????/???????????????";
        final String formatError = "??????????????????, ??????????????????2022/7/1";
        final String effectiveTimeError = "??????????????????????????????";
        final String codeLengthError = "???????????????????????????????????????20??????";

        final int codesSize = 1000;
        StringBuilder failMsg = new StringBuilder();
        ImportRedeemCodeVO importRedeemCodeVO = new ImportRedeemCodeVO();
        //????????????
        int emptyCount = 0;

        //????????????code
        Map<String, String> verifyCode = weRedeemCodeList.stream().collect(Collectors.toMap(WeRedeemCode::getCode, WeRedeemCode::getActivityId));

        //???????????????sheet
        XSSFSheet sheet = workbook.getSheetAt(0);
        int lastRowNum = sheet.getLastRowNum();
        if (lastRowNum > codesSize) {
            failMsg.append(overSize);
        }
        //???????????????Excel??????
        for (int rowNum = 1; rowNum <= lastRowNum; rowNum++) {
            XSSFRow row = sheet.getRow(rowNum);
            //???????????????
            if (ObjectUtil.isEmpty(row.getCell(0))) {
                emptyCount++;
                failMsg.append("??? ").append(rowNum + 1).append(" ???,").append(contentEmpty).append("\r\n");
                continue;
            }
            row.getCell(0).setCellType(CellType.STRING);
            String code = row.getCell(0).getStringCellValue();
            //????????????????????????????????????20
            if (code.length() > 20) {
                failMsg.append("??? ").append(rowNum + 1).append(" ???,").append(codeLengthError).append("\r\n");
                continue;
            }
            String effectiveTime;

            if (ObjectUtil.isNotEmpty(row.getCell(1))) {
                //?????????????????????
                if (STRING.name().equals(row.getCell(1).getCellTypeEnum().name())) {
                    row.getCell(1).setCellType(CellType.STRING);
                    effectiveTime = row.getCell(1).getStringCellValue();
                } else if (DateUtil.isCellDateFormatted(row.getCell(1))) {
                    if (ObjectUtil.isEmpty(row.getCell(1).getDateCellValue())) {
                        effectiveTime = WeConstans.REDEEM_CODE_EMPTY_TIME;
                    } else {
                        Date date = row.getCell(1).getDateCellValue();
                        effectiveTime = DateFormatUtils.format(date, "yyyy/M/d");
                    }
                } else {
                    effectiveTime = row.getCell(1).getStringCellValue();
                }
                if (!WeConstans.REDEEM_CODE_EMPTY_TIME.equals(effectiveTime)) {
                    //??????????????????
                    if (Boolean.TRUE.equals(!DateUtils.isMatchFormat(effectiveTime, "yyyy/MM/dd")
                            && !DateUtils.isMatchFormat(effectiveTime, "yyyy/MM/d")
                            && !DateUtils.isMatchFormat(effectiveTime, "yyyy/M/dd")
                            && !DateUtils.isMatchFormat(effectiveTime, "yyyy/M/d"))) {
                        failMsg.append("??? ").append(rowNum + 1).append(" ???,").append(formatError).append("\r\n");
                        continue;
                    }
                }
            } else {
                effectiveTime = WeConstans.REDEEM_CODE_EMPTY_TIME;
            }
            //??????code??????
            if (StringUtils.isNotEmpty(verifyCode.get(code))) {
                failMsg.append("??? ").append(rowNum + 1).append(" ???,").append(repeatCode).append("\r\n");
                continue;
            }
            verifyCode.put(code, activityId);
            WeRedeemCodeImportDTO weRedeemCodeImportDTO = WeRedeemCodeImportDTO.builder()
                    .activityId(Long.valueOf(activityId))
                    .code(code)
                    .effectiveTime(effectiveTime).build();
            redeemCodeImport.add(weRedeemCodeImportDTO);
        }

        if (lastRowNum == emptyCount) {
            throw new CustomException(ResultTip.TIP_REDEEM_CODE_FILE_DATA_IS_EMPTY);
        }

        importRedeemCodeVO.setSuccessNum(redeemCodeImport.size());
        importRedeemCodeVO.setFailNum(lastRowNum - redeemCodeImport.size());
        if (importRedeemCodeVO.getFailNum() > 0) {
            String suffix = "txt";
            String fileName = System.currentTimeMillis() + new Random().nextInt(codesSize) + ".txt";
            RuoYiConfig ruoyiConfig = SpringUtils.getBean(RuoYiConfig.class);
            CosConfig cosConfig = ruoyiConfig.getFile().getCos();
            try {
                String url = FileUploadUtils.upload2Cos(new ByteArrayInputStream(failMsg.toString().getBytes(StandardCharsets.UTF_8)), fileName, suffix, cosConfig);
                String imgUrlPrefix = ruoyiConfig.getFile().getCos().getCosImgUrlPrefix();
                importRedeemCodeVO.setUrl(imgUrlPrefix + url);
            } catch (IOException e) {
                log.error("??????????????????????????????????????????ex:{}", ExceptionUtils.getStackTrace(e));
            }
        }
        return importRedeemCodeVO;
    }

    /**
     * ???????????????
     *
     * @param weRedeemCodeDTO
     */
    @Override
    public void saveRedeemCode(WeRedeemCodeDTO weRedeemCodeDTO) {
        if (ObjectUtil.isNull(weRedeemCodeDTO)) {
            throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
        }
        WeRedeemCode weRedeemCode = weRedeemCodeDTO.setAddOrUpdateWeRedeemCode();
        weRedeemCode.setCorpId(LoginTokenService.getLoginUser().getCorpId());
        if (ObjectUtil.isNotNull(this.baseMapper.selectOne(weRedeemCode))) {
            throw new CustomException(ResultTip.TIP_REDEEM_CODE_REPEAT);
        }
        this.baseMapper.insertWeRedeemCode(weRedeemCode);
    }

    /**
     * ?????????????????????
     *
     * @param weRedeemCodeDTO
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateRedeemCode(WeRedeemCodeDTO weRedeemCodeDTO) {
        if (ObjectUtil.isNull(weRedeemCodeDTO)) {
            throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
        }
        String corpId = weRedeemCodeDTO.getCorpId();
        WeRedeemCode weRedeemCode = weRedeemCodeDTO.setAddOrUpdateWeRedeemCode();
        if (StringUtils.isNotBlank(weRedeemCode.getReceiveUserId())) {
            //???????????????????????????????????????, ????????????????????????????????????
            WeRedeemCode getWeRedeemCode = WeRedeemCode.builder().activityId(String.valueOf(weRedeemCodeDTO.getActivityId())).receiveUserId(weRedeemCodeDTO.getReceiveUserId()).build();
            final WeRedeemCode selectWeRedeemCode = weRedeemCodeMapper.selectOne(getWeRedeemCode);
            final WeRedeemCodeActivityVO redeemCodeActivity = weRedeemCodeActivityService.getRedeemCodeActivity(corpId, Long.valueOf(weRedeemCode.getActivityId()));
            if (WeConstans.REDEEM_CODE_ACTIVITY_LIMITED.equals(redeemCodeActivity.getEnableLimited()) && ObjectUtil.isNotEmpty(selectWeRedeemCode)) {
                throw new CustomException(ResultTip.TIP_REDEEM_CODE_ACTIVITY_LIMIT_ADD_USER);
            }
            weRedeemCode.setStatus(WeConstans.REDEEM_CODE_RECEIVED);
            weRedeemCode.setRedeemTime(DateUtils.parseDateToStr(DateUtils.YYYY_MM_DD_HH_MM_SS, new Date()));
        } else {
            weRedeemCode.setStatus(WeConstans.REDEEM_CODE_NOT_RECEIVED);
            weRedeemCode.setRedeemTime(WeConstans.REDEEM_CODE_EMPTY_TIME);
        }
        if (StringUtils.isBlank(weRedeemCode.getEffectiveTime())) {
            weRedeemCode.setEffectiveTime(WeConstans.REDEEM_CODE_EMPTY_TIME);
        }
        this.baseMapper.updateWeRedeemCode(weRedeemCode);
        //????????????
        alarmUser(corpId, weRedeemCodeDTO.getActivityId());
    }


    /**
     * ??????
     *
     * @param corpId
     * @param activityId
     */
    private void alarmUser(String corpId, Long activityId) {
        //??????????????????????????????????????????????????????
        WeRedeemCodeActivityVO redeemCodeActivity = weRedeemCodeActivityService.getRedeemCodeActivity(corpId, activityId);
        if (ObjectUtil.isNull(redeemCodeActivity)
                || ObjectUtil.isNull(redeemCodeActivity.getActivityName())
                || ObjectUtil.isNull(redeemCodeActivity.getRemainInventory())
                || ObjectUtil.isNull(redeemCodeActivity.getEnableLimited())) {
            log.debug("???????????????????????????,????????????????????????????????????id???{}", activityId);
            return;
        }
        if (WeConstans.REDEEM_CODE_USER_ALARM.equals(redeemCodeActivity.getEnableAlarm())) {
            //??????????????????????????????
            final Integer remainInventory = redeemCodeActivity.getRemainInventory();
            final Integer alarmThreshold = redeemCodeActivity.getAlarmThreshold();
            log.info("???????????????id:{},????????????:{},???????????????{}", redeemCodeActivity.getId(), remainInventory, alarmThreshold);
            if (remainInventory.equals(alarmThreshold)) {
                log.debug("???????????????{},????????????,?????????????????????", redeemCodeActivity.getId());
                if (CollectionUtils.isEmpty(redeemCodeActivity.getAlarmUserList())) {
                    log.error("[?????????????????????????????????,????????????]");
                } else {
                    String alarmMsg;
                    alarmMsg = WeConstans.REDEEM_CODE_ALARM_MESSAGE_INFO.replaceAll(WeConstans.REDEEM_CODE_ACTIVITY_NAME, redeemCodeActivity.getActivityName())
                            .replaceAll(WeConstans.REDEEM_CODE_REAMIN_INVENTORY, String.valueOf(remainInventory));
                    redeemCodeActivity.getAlarmUserList().forEach(item -> {
                        toAlarmUser(corpId, item.getTargetId(), alarmMsg);
                    });
                }
            }
        }
    }

    /**
     * ???????????????????????????
     *
     * @param corpId
     * @param userId
     * @param msg
     */
    private void toAlarmUser(String corpId, String userId, String msg) {
        WeMessagePushDTO pushDto = new WeMessagePushDTO();
        WeCorpAccount validWeCorpAccount = corpAccountService.findValidWeCorpAccount(corpId);
        String agentId = validWeCorpAccount.getAgentId();
        // ????????????
        TextMessageDTO text = new TextMessageDTO();
        StringBuilder content = new StringBuilder();
        //??????????????? ?????????????????????
        pushDto.setTouser(userId);
        content.append(msg);
        text.setContent(content.toString());
        pushDto.setAgentid(Integer.valueOf(agentId));
        pushDto.setText(text);
        pushDto.setMsgtype(MessageType.TEXT.getMessageType());
        // ??????????????????????????????????????? [???????????? - ??????????????????]
        log.debug("??????????????????????????????????????????toUser:{}", userId);
        messagePushClient.sendMessageToUser(pushDto, agentId, corpId);
    }

    /**
     * ?????????????????????
     *
     * @param deleteDTO
     * @return
     */
    @Override
    public int batchRemoveRedeemCode(WeRedeemCodeDeleteDTO deleteDTO) {

        if (ObjectUtil.isNull(deleteDTO.getActivityId()) || CollectionUtils.isEmpty(deleteDTO.getCodeList())) {
            throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
        }
        return this.baseMapper.delete(new LambdaQueryWrapper<WeRedeemCode>()
                .eq(WeRedeemCode::getActivityId, deleteDTO.getActivityId())
                .in(WeRedeemCode::getCode, deleteDTO.getCodeList()));
    }

    /**
     * ???????????????????????????
     *
     * @param weRedeemCodeDTO
     * @return
     */
    @Override
    public List<WeRedeemCodeVO> getReemCodeList(WeRedeemCodeDTO weRedeemCodeDTO) {
        if (ObjectUtil.isNull(weRedeemCodeDTO) || ObjectUtil.isNull(weRedeemCodeDTO.getActivityId())) {
            throw new CustomException(ResultTip.TIP_GENERAL_BAD_REQUEST);
        }
        if (StringUtils.isNotBlank(weRedeemCodeDTO.getReceiveName())) {
            List<WeCustomerVO> customers = weCustomerService.getCustomer(weRedeemCodeDTO.getCorpId(), weRedeemCodeDTO.getReceiveName());
            List<String> externalUsers = customers.stream().map(WeCustomerVO::getExternalUserid).collect(Collectors.toList());
            weRedeemCodeDTO.setExternalUserIdList(externalUsers);
        }
        startPage();
        List<WeRedeemCodeVO> weRedeemCodeList = this.baseMapper.selectWeRedeemCodeList(weRedeemCodeDTO);
        weRedeemCodeList.forEach(item -> {
            if (item.getRedeemTime().contains(WeConstans.REDEEM_CODE_EMPTY_TIME)) {
                item.setRedeemTime(StringUtils.EMPTY);
            }
            if (item.getEffectiveTime().contains(WeConstans.REDEEM_CODE_EMPTY_TIME)) {
                item.setEffectiveTime(StringUtils.EMPTY);
            }
        });
        return weRedeemCodeList;
    }

    /**
     * ??????
     */
    public void startPage() {
        PageDomain pageDomain = TableSupport.buildPageRequest();
        Integer pageNum = pageDomain.getPageNum();
        Integer pageSize = pageDomain.getPageSize();
        if (com.easywecom.common.utils.StringUtils.isNotNull(pageNum) && com.easywecom.common.utils.StringUtils.isNotNull(pageSize)) {
            String orderBy = SqlUtil.escapeOrderBySql(pageDomain.getOrderBy());
            PageHelper.startPage(pageNum, pageSize, orderBy);
        }
    }

}
