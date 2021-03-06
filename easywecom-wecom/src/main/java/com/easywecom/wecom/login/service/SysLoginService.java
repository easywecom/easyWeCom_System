package com.easywecom.wecom.login.service;

import cn.hutool.core.util.ObjectUtil;
import com.easywecom.common.config.RuoYiConfig;
import com.easywecom.common.constant.Constants;
import com.easywecom.common.core.domain.entity.WeCorpAccount;
import com.easywecom.common.core.domain.model.LoginResult;
import com.easywecom.common.core.domain.model.LoginUser;
import com.easywecom.common.core.domain.wecom.WeUser;
import com.easywecom.common.core.redis.RedisCache;
import com.easywecom.common.enums.LoginTypeEnum;
import com.easywecom.common.enums.ResultTip;
import com.easywecom.common.exception.CustomException;
import com.easywecom.common.exception.user.CaptchaException;
import com.easywecom.common.exception.user.CaptchaExpireException;
import com.easywecom.common.exception.user.QrCodeLoginException;
import com.easywecom.common.exception.user.UserPasswordNotMatchException;
import com.easywecom.common.manager.AsyncManager;
import com.easywecom.common.manager.factory.AsyncFactory;
import com.easywecom.common.token.SysPermissionService;
import com.easywecom.common.token.TokenService;
import com.easywecom.common.utils.MessageUtils;
import com.easywecom.common.utils.ServletUtils;
import com.easywecom.wecom.client.We3rdUserClient;
import com.easywecom.wecom.client.WeAccessTokenClient;
import com.easywecom.wecom.client.WeUserClient;
import com.easywecom.wecom.domain.WeExternalUserMappingUser;
import com.easywecom.wecom.domain.dto.WeAccessUserInfo3rdDTO;
import com.easywecom.wecom.domain.dto.WeLoginUserInfoDTO;
import com.easywecom.wecom.domain.dto.WeUserDTO;
import com.easywecom.wecom.domain.dto.WeUserInfoDTO;
import com.easywecom.wecom.mapper.WeDepartmentMapper;
import com.easywecom.wecom.service.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import javax.servlet.http.Cookie;
import javax.validation.constraints.NotNull;
import java.util.Map;


/**
 * ??????????????????
 *
 * @author admin
 */
@Slf4j
@Component
public class SysLoginService {

    @Resource
    private AuthenticationManager authenticationManager;
    private final WeUserClient weUserClient;
    private final WeAccessTokenClient weAccessTokenClient;
    private final WeUserService weUserService;
    private final SysPermissionService permissionService;
    private final TokenService tokenService;
    private final WeDepartmentMapper weDepartmentMapper;
    private final WeAuthCorpInfoService weAuthCorpInfoService;
    private final WeCorpAccountService weCorpAccountService;
    private final We3rdUserClient we3rdUserClient;
    private final RedisCache redisCache;
    private final RuoYiConfig ruoYiConfig;
    private final We3rdAppService we3rdAppService;

    @Autowired
    private WeExternalUserMappingUserService weExternalUserMappingUserService;

    @Autowired
    public SysLoginService(@NotNull WeUserClient weUserClient, @NotNull WeUserService weUserService,
                           @NotNull SysPermissionService permissionService, @NotNull TokenService tokenService,
                           @NotNull WeDepartmentMapper weDepartmentMapper, WeAccessTokenClient weAccessTokenClient, WeAuthCorpInfoService weAuthCorpInfoService, WeCorpAccountService weCorpAccountService, We3rdUserClient we3rdUserClient, RedisCache redisCache, RuoYiConfig ruoYiConfig, We3rdAppService we3rdAppService) {
        this.weUserClient = weUserClient;
        this.weUserService = weUserService;
        this.permissionService = permissionService;
        this.tokenService = tokenService;
        this.weDepartmentMapper = weDepartmentMapper;
        this.weAccessTokenClient = weAccessTokenClient;
        this.weAuthCorpInfoService = weAuthCorpInfoService;
        this.weCorpAccountService = weCorpAccountService;
        this.we3rdUserClient = we3rdUserClient;
        this.redisCache = redisCache;
        this.ruoYiConfig = ruoYiConfig;
        this.we3rdAppService = we3rdAppService;
    }

    /**
     * ????????????
     *
     * @param username ?????????
     * @param password ??????
     * @param code     ?????????
     * @param uuid     ????????????
     * @return ??????
     */
    public String login(String username, String password, String code, String uuid) {

        String verifyKey = Constants.CAPTCHA_CODE_KEY + uuid;
        String captcha = redisCache.getCacheObject(verifyKey);
        redisCache.deleteObject(verifyKey);
        WeCorpAccount weCorpAccount = weCorpAccountService.findValidWeCorpAccount();
        String corpId = weCorpAccountService.getCorpId(weCorpAccount);
        if (captcha == null) {
            AsyncManager.me().execute(AsyncFactory.recordLogininfor(corpId, username, Constants.LOGIN_FAIL, MessageUtils.message("user.jcaptcha.expire"), LoginTypeEnum.BY_PASSWORD.getType()));
            throw new CaptchaExpireException();
        }
        if (!code.equalsIgnoreCase(captcha)) {
            AsyncManager.me().execute(AsyncFactory.recordLogininfor(corpId, username, Constants.LOGIN_FAIL, MessageUtils.message("user.jcaptcha.error"), LoginTypeEnum.BY_PASSWORD.getType()));
            throw new CaptchaException();
        }

        //???????????????????????????????????????
        if (ruoYiConfig.isThirdServer()) {
            AsyncManager.me().execute(AsyncFactory.recordLogininfor(corpId, username, Constants.LOGIN_FAIL, MessageUtils.message("server.not.support"), LoginTypeEnum.BY_PASSWORD.getType()));
            throw new CustomException("?????????????????????????????????");
        }

        // ????????????
        Authentication authentication;
        try {
            // ?????????????????????UserDetailsServiceImpl.loadUserByUsername
            authentication = authenticationManager
                    .authenticate(new UsernamePasswordAuthenticationToken(username, password));
        } catch (Exception e) {
            if (e instanceof BadCredentialsException) {
                AsyncManager.me().execute(AsyncFactory.recordLogininfor(corpId, username, Constants.LOGIN_FAIL, MessageUtils.message("user.password.not.match"), LoginTypeEnum.BY_PASSWORD.getType()));
                throw new UserPasswordNotMatchException();
            } else {
                AsyncManager.me().execute(AsyncFactory.recordLogininfor(corpId, username, Constants.LOGIN_FAIL, e.getMessage(), LoginTypeEnum.BY_PASSWORD.getType()));
                throw new CustomException(ResultTip.TIP_GENERAL_ERROR, e.getMessage());
            }
        }
        AsyncManager.me().execute(AsyncFactory.recordLogininfor(corpId, username, Constants.LOGIN_SUCCESS, MessageUtils.message("user.login.success"), LoginTypeEnum.BY_PASSWORD.getType()));
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        //?????????????????????????????????????????????????????????corpId???????????????????????????????????????????????????????????????
        loginUser.getUser().setCorpId(corpId);
        // ??????token
        return tokenService.createToken(loginUser);
    }



    /**
     * ??????????????????
     *
     * @param code  ????????????code
     * @param state ??????state
     * @return token ????????????????????????token
     */
    public LoginResult qrCodeLogin(String code, String state) {
        log.info("?????????????????????code:{},state:{}", code, state);
        //????????????????????????????????????
        if (ruoYiConfig.isThirdServer()) {
            throw new CustomException(ResultTip.TIP_SERVER_NOT_SUPPORT);
        }
        WeCorpAccount weCorpAccount = weCorpAccountService.findValidWeCorpAccount();
        String corpId = weCorpAccountService.getCorpId(weCorpAccount);
        if (StringUtils.isBlank(code)) {
            AsyncManager.me().execute(AsyncFactory.recordLogininfor(corpId, Constants.QR_CODE_SCAN_USER, Constants.LOGIN_FAIL, MessageUtils.message(Constants.GET_CODE_FAIL), LoginTypeEnum.BY_SCAN.getType()));
            throw new QrCodeLoginException(Constants.GET_CODE_FAIL);
        }
        // 1. ??????CODE ??????API??????????????????userId
        if (ObjectUtil.isEmpty(weCorpAccount) || StringUtils.isBlank(weCorpAccount.getCorpId()) || StringUtils.isBlank(weCorpAccount.getContactSecret())) {
            throw new CustomException(ResultTip.TIP_NOT_CONFIG_CONTACT);
        }
        WeUserInfoDTO weUserInfoDTO = weUserClient.getQrCodeLoginUserInfo(code, weCorpAccount.getCorpId());
        if (null == weUserInfoDTO) {
            AsyncManager.me().execute(AsyncFactory.recordLogininfor(corpId, Constants.QR_CODE_SCAN_USER, Constants.LOGIN_FAIL, MessageUtils.message(Constants.GET_INFO_FAIL), LoginTypeEnum.BY_SCAN.getType()));
            throw new QrCodeLoginException(Constants.GET_INFO_FAIL);
        }
        String userId = weUserInfoDTO.getUserId();
        return loginByUserId(userId, corpId, weCorpAccount, Boolean.TRUE, Boolean.FALSE);
    }

    /**
     * ??????userId??????
     *
     * @param userId                ??????ID
     * @param corpId                ??????ID???qrCodeLogin3rd??????????????????ID???qrCodeLogin????????????ID???
     * @param configuredInternalApp ????????????????????????
     * @param isThirdLogin          ??????????????????
     * @return {@link LoginResult}
     */
    public LoginResult loginByUserId(String userId, String corpId, WeCorpAccount weCorpAccount, Boolean configuredInternalApp, Boolean isThirdLogin) {
        if (StringUtils.isBlank(corpId)) {
            throw new CustomException(ResultTip.TIP_MISS_CORP_ID);
        }
        if (StringUtils.isBlank(userId)) {
            AsyncManager.me().execute(AsyncFactory.recordLogininfor(corpId, Constants.QR_CODE_SCAN_USER, Constants.LOGIN_FAIL, MessageUtils.message(Constants.NOT_IN_COMPANY), LoginTypeEnum.BY_SCAN.getType()));
            throw new QrCodeLoginException(Constants.NOT_IN_COMPANY);
        }

        WeUser weUser;
        if (Boolean.FALSE.equals(isThirdLogin)) {
            WeUserDTO weUserDTO = weUserClient.getUserByUserId(userId, corpId);
            if (weUserDTO == null) {
                AsyncManager.me().execute(AsyncFactory.recordLogininfor(corpId, Constants.QR_CODE_SCAN_USER, Constants.LOGIN_FAIL, MessageUtils.message(Constants.GET_INFO_FAIL), LoginTypeEnum.BY_SCAN.getType()));
                throw new QrCodeLoginException(Constants.GET_INFO_FAIL);
            }
            weUser = weUserDTO.transferToWeUser();
            weUser.setCorpId(corpId);
            weUserService.insertWeUserNoToWeCom(weUser);
            weUser.setDepartmentName(weDepartmentMapper.selectNameByUserId(corpId, userId));
        } else {
            weUser = this.thirdGetWeUser(userId, corpId, configuredInternalApp, weCorpAccount);
        }

        // 3. ?????? ??????????????????
        LoginUser loginUser = new LoginUser(weUser, permissionService.getMenuPermission(weUser));
        if (weCorpAccount != null) {
            loginUser.setCorpName(weCorpAccount.getCompanyName());
        } else {
            loginUser.setCorpName("");
        }
        AsyncManager.me().execute(AsyncFactory.recordLogininfor(corpId, weUser.getName(), Constants.LOGIN_SUCCESS, MessageUtils.message(Constants.USER_LOGIN_SUCCESS), LoginTypeEnum.BY_SCAN.getType()));
        // 4. ?????????????????? ??????token
        String token = tokenService.createToken(loginUser);
        Cookie cookie = new Cookie("Admin-Token", token);
        cookie.setHttpOnly(true);
        ServletUtils.getResponse().addCookie(cookie);
        return new LoginResult(token, loginUser);
    }


    private WeUser thirdGetWeUser(String externalUserId, String externalCorpId, Boolean configuredInternalApp, WeCorpAccount weCorpAccount) {

        //?????????????????????????????????userId????????????
        if (Boolean.TRUE.equals(configuredInternalApp) && Constants.NORMAL_CODE.equals(weCorpAccount.getStatus()) && StringUtils.isNotBlank(weCorpAccount.getContactSecret())) {
            weExternalUserMappingUserService.initMapping(weCorpAccount.getCorpId());
            WeExternalUserMappingUser weExternalUserMappingUser;
            if (externalCorpId.equals(weCorpAccount.getCorpId())) {
                weExternalUserMappingUser = weExternalUserMappingUserService.getMappingByInternal(externalCorpId, externalUserId);
            } else {
                weExternalUserMappingUser = weExternalUserMappingUserService.getMappingByExternal(externalCorpId, externalUserId);
            }

            if (weExternalUserMappingUser != null && StringUtils.isNoneBlank(weExternalUserMappingUser.getUserId(), weExternalUserMappingUser.getCorpId())) {
                WeUserDTO weUserDTO = weUserClient.getUserByUserId(weExternalUserMappingUser.getUserId(), weExternalUserMappingUser.getCorpId());
                if (weUserDTO == null) {
                    AsyncManager.me().execute(AsyncFactory.recordLogininfor(weExternalUserMappingUser.getCorpId(), Constants.QR_CODE_SCAN_USER, Constants.LOGIN_FAIL, MessageUtils.message(Constants.GET_INFO_FAIL), LoginTypeEnum.BY_SCAN.getType()));
                    throw new QrCodeLoginException(Constants.GET_INFO_FAIL);
                }
                WeUser weUser = weUserDTO.transferToWeUser();
                weUser.setExternalCorpId(externalCorpId);
                weUser.setExternalUserId(externalUserId);
                weUser.setCorpId(weExternalUserMappingUser.getCorpId());
                weUserService.insertWeUserNoToWeCom(weUser);
                weUser.setDepartmentName(weDepartmentMapper.selectNameByUserId(weExternalUserMappingUser.getCorpId(), weExternalUserMappingUser.getUserId()));
                return weUser;
            }
        }
        WeUserDTO weUserDTO = we3rdUserClient.getUserByUserId(externalCorpId, externalUserId);
        if (weUserDTO == null) {
            AsyncManager.me().execute(AsyncFactory.recordLogininfor(externalCorpId, Constants.QR_CODE_SCAN_USER, Constants.LOGIN_FAIL, MessageUtils.message(Constants.GET_INFO_FAIL), LoginTypeEnum.BY_SCAN.getType()));
            throw new QrCodeLoginException(Constants.GET_INFO_FAIL);
        }
        if (Boolean.FALSE.equals(configuredInternalApp)) {
            Map<String, Integer> adminMap = we3rdAppService.getAdminList(externalCorpId);
            if (ObjectUtil.isEmpty(adminMap) || !adminMap.containsKey(weUserDTO.getUserid())) {
                //???????????????????????????????????????????????????????????????????????????????????????????????????????????????
                throw new CustomException(ResultTip.TIP_NOT_CONFIG_CONTACT);
            }
        }
        WeUser weUser = weUserDTO.transferToWeUser();
        if (weCorpAccount != null && StringUtils.isNotBlank(weCorpAccount.getCorpId())) {
            weUser.setCorpId(weCorpAccount.getCorpId());
        } else {
            weUser.setCorpId(externalCorpId);
        }
        weUser.setExternalCorpId(externalCorpId);
        weUser.setExternalUserId(externalUserId);
        weUserService.insertWeUserNoToWeCom(weUser);
        //?????????????????????????????????
        weUser.setDepartmentName("");
        return weUser;
    }

    public LoginResult loginHandler(String code, String state) {
        if (LoginTypeEnum.BY_THIRD_SCAN.getState().equals(state)||LoginTypeEnum.BY_WEB.getState().equals(state)){
            return thirdLogin(code,state);
        }else {
            return qrCodeLogin(code,state);
        }
    }

    private LoginResult thirdLogin(String code, String state){
        if (!LoginTypeEnum.BY_THIRD_SCAN.getState().equals(state) && !LoginTypeEnum.BY_WEB.getState().equals(state)){
            return null;
        }
        log.info("???????????????code:{},state:{}", code, state);
        if (StringUtils.isBlank(code)) {
            throw new QrCodeLoginException(Constants.GET_CODE_FAIL);
        }
        WeUser weUser;
        if (LoginTypeEnum.BY_WEB.getState().equals(state)){
            weUser = webLoginHandler(code);
        }else {
            weUser = qrCode3rdLoginHandler(code);
        }
        //???????????????????????????????????????
        if (!weAuthCorpInfoService.corpAuthorized(weUser.getCorpId())) {
            //?????????
            throw new CustomException(ResultTip.TIP_NOT_AUTH_CORP);
        }
        //???????????????????????????????????????????????????
        boolean configuredInternalApp = false;
        WeCorpAccount weCorpAccount = weCorpAccountService.internalAppConfigured(weUser.getCorpId());
        if (weCorpAccount != null) {
            configuredInternalApp = true;
        }
        //??????
        return loginByUserId(weUser.getUserId(), weUser.getCorpId(), weCorpAccount, configuredInternalApp, Boolean.TRUE);
    }

    private WeUser webLoginHandler(String code){
        // 1. ??????CODE ??????API??????????????????userId
        WeAccessUserInfo3rdDTO weAccessUserInfo3rdDTO = we3rdUserClient.getuserinfo3rd(code);
        if (null == weAccessUserInfo3rdDTO) {
            throw new QrCodeLoginException(Constants.GET_INFO_FAIL);
        }
        return new WeUser(weAccessUserInfo3rdDTO.getCorpId(),weAccessUserInfo3rdDTO.getUserId());
    }

    private WeUser qrCode3rdLoginHandler(String code){
        // 1. ??????CODE ??????API??????????????????userId
        WeLoginUserInfoDTO weLoginUserInfoDTO = weAccessTokenClient.getLoginInfo(code);
        if (null == weLoginUserInfoDTO) {
            throw new QrCodeLoginException(Constants.GET_INFO_FAIL);
        }
        return new WeUser(weLoginUserInfoDTO.getCorp_info().getCorpid(),weLoginUserInfoDTO.getUser_info().getUserid());
    }
}
