package com.propertyops.pms.iam;

import static com.propertyops.pms.iam.IamModels.*;

import java.util.List;

import javax.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/iam")
public class IamAdminController {
    private final IamAdminService service;

    public IamAdminController(IamAdminService service) {
        this.service = service;
    }

    @GetMapping("/enterprises")
    List<EnterpriseView> enterprises() {
        return service.enterprises();
    }

    @PostMapping("/enterprises")
    EnterpriseView createEnterprise(@Valid @RequestBody CreateEnterpriseRequest request) {
        return service.createEnterprise(request);
    }

    @PutMapping("/enterprises/{id}")
    EnterpriseView updateEnterprise(@PathVariable String id,
                                    @Valid @RequestBody UpdateEnterpriseRequest request) {
        return service.updateEnterprise(id, request);
    }

    @GetMapping("/organizations")
    List<OrganizationView> organizations(@RequestParam(required = false) String enterpriseId) {
        return service.organizations(enterpriseId);
    }

    @PostMapping("/organizations")
    OrganizationView createOrganization(@Valid @RequestBody CreateOrganizationRequest request) {
        return service.createOrganization(request);
    }

    @PutMapping("/organizations/{id}")
    OrganizationView updateOrganization(@PathVariable String id,
                                        @Valid @RequestBody UpdateOrganizationRequest request) {
        return service.updateOrganization(id, request);
    }

    @GetMapping("/positions")
    List<PositionView> positions(@RequestParam(required = false) String enterpriseId) {
        return service.positions(enterpriseId);
    }

    @PostMapping("/positions")
    PositionView createPosition(@Valid @RequestBody CreatePositionRequest request) {
        return service.createPosition(request);
    }

    @PutMapping("/positions/{id}")
    PositionView updatePosition(@PathVariable String id, @Valid @RequestBody UpdatePositionRequest request) {
        return service.updatePosition(id, request);
    }

    @GetMapping("/employees")
    List<EmployeeView> employees(@RequestParam(required = false) String enterpriseId) {
        return service.employees(enterpriseId);
    }

    @PostMapping("/employees")
    EmployeeView createEmployee(@Valid @RequestBody CreateEmployeeRequest request) {
        return service.createEmployee(request);
    }

    @PutMapping("/employees/{id}")
    EmployeeView updateEmployee(@PathVariable String id, @Valid @RequestBody UpdateEmployeeRequest request) {
        return service.updateEmployee(id, request);
    }

    @GetMapping("/permissions")
    List<PermissionView> permissions() {
        return service.permissions();
    }

    @GetMapping("/roles")
    List<RoleView> roles() {
        return service.roles();
    }

    @PostMapping("/roles")
    RoleView createRole(@Valid @RequestBody CreateRoleRequest request) {
        return service.createRole(request);
    }

    @PutMapping("/roles/{id}")
    RoleView updateRole(@PathVariable String id, @Valid @RequestBody UpdateRoleRequest request) {
        return service.updateRole(id, request);
    }

    @GetMapping("/users")
    List<UserView> users() {
        return service.users();
    }

    @PostMapping("/users")
    UserView createUser(@Valid @RequestBody CreateUserRequest request) {
        return service.createUser(request);
    }

    @PutMapping("/users/{id}")
    UserView updateUser(@PathVariable String id, @Valid @RequestBody UpdateUserRequest request) {
        return service.updateUser(id, request);
    }

    @PutMapping("/users/{id}/password")
    UserView resetPassword(@PathVariable String id, @Valid @RequestBody ResetPasswordRequest request) {
        return service.resetPassword(id, request);
    }

    @GetMapping("/projects")
    List<ProjectView> projects() {
        return service.projects();
    }
}
