package com.github.yiuman.citrus.system.rest;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.yiuman.citrus.security.authorize.Authorize;
import com.github.yiuman.citrus.support.crud.query.Query;
import com.github.yiuman.citrus.support.crud.query.QueryParamHandler;
import com.github.yiuman.citrus.support.crud.query.QueryParamMeta;
import com.github.yiuman.citrus.support.crud.query.annotations.Like;
import com.github.yiuman.citrus.support.crud.query.annotations.QueryParam;
import com.github.yiuman.citrus.support.crud.query.builder.QueryBuilders;
import com.github.yiuman.citrus.support.crud.rest.BaseCrudController;
import com.github.yiuman.citrus.support.crud.service.CrudService;
import com.github.yiuman.citrus.support.crud.view.impl.FormView;
import com.github.yiuman.citrus.support.crud.view.impl.PageTableView;
import com.github.yiuman.citrus.support.exception.RestException;
import com.github.yiuman.citrus.support.http.ResponseEntity;
import com.github.yiuman.citrus.support.http.ResponseStatusCode;
import com.github.yiuman.citrus.support.model.Page;
import com.github.yiuman.citrus.support.utils.CrudUtils;
import com.github.yiuman.citrus.support.widget.Column;
import com.github.yiuman.citrus.support.widget.Inputs;
import com.github.yiuman.citrus.support.widget.Selects;
import com.github.yiuman.citrus.system.dto.PasswordUpdateDto;
import com.github.yiuman.citrus.system.dto.RoleDto;
import com.github.yiuman.citrus.system.dto.UserDto;
import com.github.yiuman.citrus.system.dto.UserOnlineInfo;
import com.github.yiuman.citrus.system.entity.UserOrgan;
import com.github.yiuman.citrus.system.entity.UserRole;
import com.github.yiuman.citrus.system.hook.HasLoginHook;
import com.github.yiuman.citrus.system.inject.AuthDeptIds;
import com.github.yiuman.citrus.system.mapper.UserRoleMapper;
import com.github.yiuman.citrus.system.service.RbacMixinService;
import com.github.yiuman.citrus.system.service.UserService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ???????????????
 *
 * @author yiuman
 * @date 2020/3/30
 */
@RestController
@RequestMapping("/rest/users")
@Authorize(HasLoginHook.class)
@Slf4j
public class UserController extends BaseCrudController<UserDto, Long> {

    private final RbacMixinService rbacMixinService;

    public UserController(RbacMixinService rbacMixinService) {
        this.rbacMixinService = rbacMixinService;
        setParamClass(UserQuery.class);
    }

    @Override
    protected CrudService<UserDto, Long> getService() {
        return rbacMixinService.getUserService();
    }

    @Override
    public Object createPageView() {
        UserService userService = rbacMixinService.getUserService();
        Page<UserDto> page = getPageViewData();
        List<UserDto> records = page.getRecords();

        //????????????
        Set<Long> userIds = records.stream().map(UserDto::getUserId).collect(Collectors.toSet());
        List<UserRole> userRoles = userService.getUserRolesByUserIds(userIds);
        List<UserOrgan> userOrgans = userService.getUserOrgansByUserIds(userIds);

        PageTableView<UserDto> view = new PageTableView<>();
        view.addWidget(new Inputs("?????????", "username"));

        view.addColumn("ID", "userId").align(Column.Align.start);
        view.addColumn("?????????", "username").sortable(true);
        view.addColumn("????????????", "mobile");
        view.addColumn("??????", "email");
        view.addColumn("????????????", (entity) -> userRoles.stream()
                .filter(userRole -> userRole.getUserId().equals(entity.getUserId()))
                .map(UserRole::getRoleName).filter(Objects::nonNull).collect(Collectors.joining(","))
        );
        view.addColumn("????????????", (entity) -> userOrgans.stream()
                .filter(userOrgan -> userOrgan.getUserId().equals(entity.getUserId()))
                .map(UserOrgan::getOrganName).filter(Objects::nonNull).collect(Collectors.joining(","))
        );
        view.defaultSetting();
        return view;
    }

    @Override
    public FormView createFormView() {
        FormView dialogView = new FormView();
        dialogView.addEditField("?????????", "loginId").addRule("required");
        dialogView.addEditField("?????????", "username").addRule("required");
        dialogView.addEditField("????????????", "mobile").addRule("required", "phone");
        dialogView.addEditField("??????", "email");
        dialogView.addEditField("????????????", "roleIds", CrudUtils.getWidget(this, "getRoleSelects"));
        dialogView.addEditField("????????????", "organIds", rbacMixinService.getOrganService()
                .getOrganTree("????????????", "organIds", true));
        return dialogView;
    }

    @Selects(bind = "roleIds", key = "roleId", label = "roleName", text = "????????????", multiple = true)
    public List<RoleDto> getRoleSelects() {
        return rbacMixinService.getRoleService().list();
    }

    /**
     * ??????????????????
     *
     * @return ??????????????????
     */
    @GetMapping("/current")
    public ResponseEntity<UserOnlineInfo> getCurrentUser() {
        return ResponseEntity.ok(rbacMixinService.getCurrentUserOnlineInfo());
    }

    /**
     * ??????????????????
     *
     * @param entity ????????????
     * @return Void
     */
    @PostMapping("/profile")
    public ResponseEntity<Void> saveProfile(@Validated @RequestBody UserDto entity) {
        UserService userService = rbacMixinService.getUserService();
        UserOnlineInfo currentUserOnlineInfo = userService.getCurrentUserOnlineInfo();
        if (!entity.getUserId().equals(currentUserOnlineInfo.getUserId())) {
            throw new RestException("You cannot modify non-personal data", ResponseStatusCode.BAD_REQUEST);
        }

        userService.saveProfile(entity);

        return ResponseEntity.ok();
    }

    /**
     * ????????????
     *
     * @param passwordUpdate ?????????????????????
     * @return Void
     * @throws Exception ?????????????????????
     */
    @PostMapping("/password")
    public ResponseEntity<Void> updatePassword(@Validated @RequestBody PasswordUpdateDto passwordUpdate) throws Exception {
        UserService userService = rbacMixinService.getUserService();
        userService.updatePassword(passwordUpdate.getOldPassword(), passwordUpdate.getNewPassword());
        return ResponseEntity.ok();
    }

    @Data
    static class UserQuery {

        @Like
        private String username;

        @QueryParam(handler = UserQueryHandler.class)
        private List<Long> roleIds;

        @AuthDeptIds
        private Set<Long> deptIds;

        @Component
        public static class UserQueryHandler implements QueryParamHandler {

            private final UserRoleMapper userRoleMapper;

            UserQueryHandler(UserRoleMapper userRoleMapper) {
                this.userRoleMapper = userRoleMapper;
            }

            @SuppressWarnings("unchecked")
            @Override
            public void handle(QueryParamMeta queryParamMeta, Object object, Query query) {
                Field field = queryParamMeta.getField();
                ReflectionUtils.makeAccessible(field);
                List<Long> roleIds = (List<Long>) ReflectionUtils.getField(field, object);
                if (roleIds == null) {
                    return;
                }
                List<Long> userRoles = userRoleMapper.selectList(Wrappers.<UserRole>lambdaQuery().in(UserRole::getRoleId, roleIds))
                        .stream()
                        .map(UserRole::getUserId)
                        .collect(Collectors.toList());

                if (CollectionUtils.isEmpty(userRoles)) {
                    userRoles = Collections.singletonList(0L);

                }
                QueryBuilders.wrapper(query).in("user_id", userRoles);
            }
        }

    }

}
