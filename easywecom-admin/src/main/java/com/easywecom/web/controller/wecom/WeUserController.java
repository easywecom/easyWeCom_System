package com.easywecom.web.controller.wecom;

import com.easywecom.common.annotation.Log;
import com.easywecom.common.constant.WeConstans;
import com.easywecom.common.core.controller.BaseController;
import com.easywecom.common.core.domain.AjaxResult;
import com.easywecom.common.core.domain.model.LoginUser;
import com.easywecom.common.core.domain.wecom.WeUser;
import com.easywecom.common.core.page.TableDataInfo;
import com.easywecom.common.enums.BusinessType;
import com.easywecom.common.token.TokenService;
import com.easywecom.common.utils.ServletUtils;
import com.easywecom.common.utils.spring.SpringUtils;
import com.easywecom.wecom.domain.vo.AllocateLeaveUserResp;
import com.easywecom.wecom.domain.WeUserRole;
import com.easywecom.wecom.domain.dto.BatchUpdateUserInfoDTO;
import com.easywecom.wecom.domain.dto.QueryUserDTO;
import com.easywecom.wecom.domain.dto.transfer.TransferResignedUserListDTO;
import com.easywecom.wecom.domain.vo.*;
import com.easywecom.wecom.domain.vo.transfer.TransferResignedUserVO;
import com.easywecom.wecom.login.util.LoginTokenService;
import com.easywecom.wecom.service.WeDepartmentService;
import com.easywecom.wecom.service.WeResignedTransferRecordService;
import com.easywecom.wecom.service.WeUserService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;


/**
 * 通讯录相关客户Controller
 *
 * @author admin
 * @date 2020-08-31
 */
@RestController
@RequestMapping("/wecom/user")
@Api(tags = "通讯录人员接口")
@Slf4j
public class WeUserController extends BaseController {


    private final WeUserService weUserService;
    private final WeDepartmentService weDepartmentService;
    private final WeResignedTransferRecordService weResignedTransferRecordService;

    @Autowired
    @Lazy
    public WeUserController(WeUserService weUserService,WeDepartmentService weDepartmentService, WeResignedTransferRecordService weResignedTransferRecordService) {
        this.weUserService = weUserService;
        this.weDepartmentService = weDepartmentService;
        this.weResignedTransferRecordService = weResignedTransferRecordService;
    }

    /**
     * 查询员工信息
     * @param queryUserDTO 查询条件
     * @return TableDataInfo
     */
    @GetMapping("/listOfUser")
    @ApiOperation("查询员工信息")
    public TableDataInfo<WeUserVO> listOfUser(@Validated QueryUserDTO queryUserDTO) {
        startPage();
        queryUserDTO.setCorpId(LoginTokenService.getLoginUser().getCorpId());
        return getDataTable(weUserService.listOfUser(queryUserDTO));
    }


    @GetMapping("/getUser")
    @ApiOperation("查询单个员工信息")
    public AjaxResult<WeUserVO> getUser(String userId) {
        return AjaxResult.success(weUserService.getUser(LoginTokenService.getLoginUser().getCorpId(), userId));
    }

    /**
     * 查询员工详情列表 （需要校验数据权限)
     *
     * @param weUser
     * @return
     */
    @GetMapping("/list")
    @ApiOperation("获取员工详细信息列表")
    public TableDataInfo<WeUser> list(WeUser weUser) {
        startPage();
        weUser.setCorpId(LoginTokenService.getLoginUser().getCorpId());
        List<WeUser> list = weUserService.selectWeUserList(weUser);
        return getDataTable(list);
    }

    /**
     * 查询员工简短信息列表 （无需校验功能权限和数据权限 ）
     *
     * @param weUser
     * @return 所有员工 userId和name的集合
     */
    @GetMapping("/briefList")
    @ApiOperation("获取员工id和名字列表")
    public TableDataInfo<WeUserBriefInfoVO> briefList(WeUser weUser) {
        startPage();
        weUser.setCorpId(LoginTokenService.getLoginUser().getCorpId());
        List<WeUserBriefInfoVO> list = weUserService.selectWeUserBriefInfo(weUser);
        return getDataTable(list);
    }


    /**
     * 获取通讯录相关客户详细信息
     */
    @GetMapping(value = "/{userId}")
    @ApiOperation("获取通讯录相关客户详细信息")
    public AjaxResult getInfo(@PathVariable("userId") String userId) {
        LoginUser loginUser = LoginTokenService.getLoginUser();
        return AjaxResult.success(weUserService.selectWeUserById(loginUser.getCorpId(), userId));
    }

    /**
     * 新增通讯录相关客户
     */
    @Log(title = "新增成员", businessType = BusinessType.INSERT)
    @PostMapping
    @ApiOperation("新增成员")
    public AjaxResult add(@Validated @RequestBody WeUser weUser) {
        weUser.setCorpId(LoginTokenService.getLoginUser().getCorpId());
        weUserService.insertWeUser(weUser);
        return AjaxResult.success();
    }

    @GetMapping("/getJoinQrCode")
    @ApiOperation("获取加入企业二维码")
    public AjaxResult<String> getJoinQrCode() {
        return AjaxResult.success(
                "获取成功",weUserService.getJoinQrCode(LoginTokenService.getLoginUser().getCorpId())
        );
    }

    /**
     * 修改员工
     */
    @PreAuthorize("@ss.hasPermi('contacts:organization:edit')")
    @Log(title = "修改员工信息", businessType = BusinessType.UPDATE)
    @PutMapping
    @ApiOperation("修改员工信息")
    public AjaxResult edit(@Validated @RequestBody WeUser weUser) {
        weUser.setCorpId(LoginTokenService.getLoginUser().getCorpId());
        weUserService.updateWeUser(weUser);
        if (weUser.getRoleId() != null){
            WeUserRole weUserRole = new WeUserRole();
            BeanUtils.copyProperties(weUser,weUserRole);
            weUserService.updateUserRole(weUserRole);
        }
        //修改员工信息后 刷新数据权限范围
        LoginTokenService.refreshDataScope();
        return AjaxResult.success();
    }


    /**
     * 更新员工登录账号信息
     *
     * @param weUser 员工信息
     * @return AjaxResult
     */
    @PostMapping("/editUserAccount")
    @ApiOperation("更新员工登录账号信息")
    public AjaxResult editUserAccount(@Validated @RequestBody WeUser weUser) {
        weUser.setCorpId(LoginTokenService.getLoginUser().getCorpId());
        weUserService.updateWeUser(weUser);
        return AjaxResult.success();
    }



    /**
     * 启用或者禁止
     *
     * @param weUser
     * @return
     */
    @PreAuthorize("@ss.hasPermi('contacts:organization:forbidden')")
    @Log(title = "启用禁用用户", businessType = BusinessType.UPDATE)
    @PutMapping("/startOrStop")
    @ApiOperation("是否启用(1表示启用成员，0表示禁用成员)")
    public AjaxResult startOrStop(@RequestBody WeUser weUser) {
        weUser.setCorpId(LoginTokenService.getLoginUser().getCorpId());
        weUserService.startOrStop(weUser);
        return AjaxResult.success();
    }


    /**
     * 离职已分配,新接口
     *
     * @param weLeaveUserV2VO
     * @return
     */
    @GetMapping({"/v2/leaveUserAllocateList"})
    @ApiOperation("获取离职已分配离职员工")
    @Deprecated
    public TableDataInfo leaveUserAllocateListV2(WeLeaveUserV2VO weLeaveUserV2VO) {
        startPage();
        return getDataTable(new ArrayList<>());
    }

    /**
     * 离职已分配 V3
     *
     * @param dto
     * @return
     */
    @GetMapping("/v3/leaveUserAllocateList")
    @ApiOperation("离职已分配员工列表V3")
    public TableDataInfo<TransferResignedUserVO> leaveUserAllocateListV3(TransferResignedUserListDTO dto) {
        startPage();
        dto.setCorpId(LoginTokenService.getLoginUser().getCorpId());
        return getDataTable(weResignedTransferRecordService.listOfRecord(dto));

    }

    /**
     * 离职未分配，新接口
     *
     * @param weLeaveUserV2VO
     * @return
     */
    @GetMapping({"/v2/leaveUserNoAllocateList"})
    @ApiOperation("获取离职未分配离职员工")
    @Deprecated
    public TableDataInfo<WeLeaveUserV2VO> leaveUserNoAllocateListV2(WeLeaveUserV2VO weLeaveUserV2VO) {
        startPage();
        return getDataTable(new ArrayList<>());
    }

    @GetMapping("/v3/leaveUserNoAllocateList")
    @ApiOperation("获取离职未分配离职员工V3")
    public TableDataInfo<TransferResignedUserVO> leaveUserListV3(@Validated TransferResignedUserListDTO dto) {
        LoginUser loginUser = LoginTokenService.getLoginUser();
        dto.setCorpId(loginUser.getCorpId());
        return getDataTable(weUserService.leaveUserListV3(dto));
    }

    /**
     * 离职分配，新接口
     *
     * @param weLeaveUserInfoAllocateVo
     * @return
     */
    @PutMapping({"/v2/allocateLeaveUserAboutData"})
    @ApiOperation("离职继承分配")
    @Deprecated
    public AjaxResult<AllocateLeaveUserResp> allocateLeaveUserAboutDataV2(@RequestBody WeLeaveUserInfoAllocateVO weLeaveUserInfoAllocateVo) {
        return AjaxResult.success();
    }

    /**
     * 同步成员
     *
     * @return
     */
    @PreAuthorize("@ss.hasPermi('contacts:organization:sync')")
    @GetMapping({"/synchWeUser"})
    @ApiOperation("同步成员")
    public AjaxResult syncWeUser() {
        LoginUser loginUser = LoginTokenService.getLoginUser();
        // 1. 同步部门 (同步执行)
        String userKey = SpringUtils.getBean(TokenService.class).getUserKey(ServletUtils.getRequest());
        weDepartmentService.synchWeDepartment(loginUser.getCorpId(),userKey);
        // 2. 同步成员 (异步执行)
        weUserService.syncWeUser(loginUser.getCorpId(), loginUser);
        return AjaxResult.success(WeConstans.SYNCH_TIP);
    }


    /**
     * 删除用户
     *
     * @return
     */
    @PreAuthorize("@ss.hasPermi('contacts:organization:removeMember')")
    @DeleteMapping({"/{ids}"})
    @ApiOperation("删除客户")
    public AjaxResult deleteUser(@PathVariable String[] ids) {
        weUserService.deleteUser(LoginTokenService.getLoginUser().getCorpId(), ids);
        return AjaxResult.success();
    }


    /**
     * 获取历史分配记录的成员
     *
     * @param weAllocateCustomersVo
     * @return
     */
    @GetMapping({"/getAllocateCustomers"})
    @ApiOperation("获取历史分配记录的成员")
    @Deprecated
    public TableDataInfo<WeAllocateCustomersVO> getAllocateCustomers(WeAllocateCustomersVO weAllocateCustomersVo) {
        startPage();
        return getDataTable(new ArrayList<>());
    }

    /**
     * 获取历史分配记录的成员,新接口
     *
     * @param weAllocateCustomersVo
     * @return
     */
    @GetMapping({"/v2/getAllocateCustomers"})
    @ApiOperation("获取历史分配记录的成员")
    @Deprecated
    public TableDataInfo<WeAllocateCustomersVO> getAllocateCustomersV2(WeAllocateCustomersVO weAllocateCustomersVo) {
        return getDataTable(new ArrayList<>());
    }


    /**
     * 获取历史分配记录的群
     * @deprecated 已废弃
     * @param weAllocateGroupsVo
     * @return
     */
    @GetMapping({"/getAllocateGroups"})
    @Deprecated
    public TableDataInfo getAllocateGroups(WeAllocateGroupsVO weAllocateGroupsVo) {
        return getDataTable(new ArrayList<>());
    }

    /**
     * 获取历史分配记录的群，新接口
     *
     * @param weAllocateGroupsVo
     * @return
     */
    @ApiOperation("获取历史分配记录的群")
    @GetMapping({"/v2/getAllocateGroups"})
    @Deprecated
    public TableDataInfo getAllocateGroupsV2(WeAllocateGroupsVO weAllocateGroupsVo) {
        return getDataTable(new ArrayList<>());
    }


    /**
     * 内部应用获取用户userId
     *
     * @param code
     * @return
     */
    @GetMapping("/getUserInfo")
    @ApiOperation("内部应用获取用户userId")
    public AjaxResult getUserInfo(String code, String agentId) {
        WeUserInfoVO userInfo = weUserService.getUserInfo(code, agentId, LoginTokenService.getLoginUser().getCorpId());
        return AjaxResult.success(userInfo);
    }

    @PostMapping("/batchUpdateUserInfo")
    @ApiOperation("批量修改员工信息")
    public AjaxResult<BatchUpdateUserInfoVO> batchUpdateUserInfo(@Validated @RequestBody BatchUpdateUserInfoDTO batchUpdateUserInfoDTO) {
        batchUpdateUserInfoDTO.setCorpId(LoginTokenService.getLoginUser().getCorpId());
        return AjaxResult.success(weUserService.batchUpdateUserInfo(batchUpdateUserInfoDTO));
    }
}
