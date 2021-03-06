package com.easywecom.quartz.task;

import com.easywecom.common.constant.RedisKeyConstants;
import com.easywecom.common.core.domain.entity.WeCorpAccount;
import com.easywecom.common.core.redis.RedisCache;
import com.easywecom.wecom.domain.dto.WePageStaticDataDTO;
import com.easywecom.wecom.service.PageHomeService;
import com.easywecom.wecom.service.WeCorpAccountService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @author admin
 * @description 首页数据统计
 * @date 2021/2/23 23:51
 **/
@Slf4j
@Component("PageHomeDataTask")
public class PageHomeDataTask {
    private final RedisCache redisCache;

    private final PageHomeService pageHomeService;

    private final WeCorpAccountService weCorpAccountService;

    @Autowired
    public PageHomeDataTask(PageHomeService pageHomeService, WeCorpAccountService weCorpAccountService, RedisCache redisCache) {
        this.pageHomeService = pageHomeService;
        this.weCorpAccountService = weCorpAccountService;
        this.redisCache = redisCache;
    }

    /**
     * 该方法由quartz直接调用
     */
    public void getPageHomeDataData() {
        log.info("定时任务开始执行------>");
        List<WeCorpAccount> weCorpAccountList = weCorpAccountService.listOfAuthCorpInternalWeCorpAccount();
        weCorpAccountList.forEach(weCorpAccount -> {
            if (weCorpAccount != null && StringUtils.isNotBlank(weCorpAccount.getCorpId())) {
                pageHomeService.getCorpBasicData(weCorpAccount.getCorpId());
                getCorpRealTimeData(weCorpAccount.getCorpId());
            }
        });
        log.info("定时任务执行完成------>");
    }

    /**
     * 统计首页实时数据到redis缓存中
     */
    private void getCorpRealTimeData(String corpId) {
        if (StringUtils.isBlank(corpId)) {
            log.error("corpId不允许为空。");
            return;
        }
        WePageStaticDataDTO wePageStaticDataDTO = pageHomeService.initCorpRealTimeData(corpId);
        redisCache.setCacheObject(RedisKeyConstants.CORP_REAL_TIME + corpId, wePageStaticDataDTO);
    }


}
