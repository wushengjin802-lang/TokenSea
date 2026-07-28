package com.tokensea.organization;

import com.tokensea.app.controller.AppController;
import com.tokensea.app.entity.AppEntity;
import com.tokensea.app.mapper.AppEntityMapper;
import com.tokensea.audit.service.AuditService;
import com.tokensea.organization.service.ResourceLinkageService;
import com.tokensea.project.controller.ProjectController;
import com.tokensea.project.entity.Project;
import com.tokensea.project.mapper.ProjectMapper;
import com.tokensea.tenant.entity.Tenant;
import com.tokensea.tenant.mapper.TenantMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActiveHierarchyControllerTests {
    @Test
    void activeProjectCannotBeCreatedUnderDraftTenant() {
        ProjectMapper projects = mock(ProjectMapper.class);
        TenantMapper tenants = mock(TenantMapper.class);
        Tenant tenant = new Tenant();
        tenant.setId("tenant-draft");
        tenant.setStatus("DRAFT");
        when(tenants.selectById("tenant-draft")).thenReturn(tenant);
        ProjectController controller = new ProjectController(
                projects, tenants, mock(AuditService.class), mock(ResourceLinkageService.class));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.create(new ProjectController.Request(
                        "tenant-draft", "Project", "Owner", null)));

        assertEquals(409, error.getStatusCode().value());
        verify(projects, never()).insert(any(Project.class));
    }

    @Test
    void activeAppCannotBeCreatedUnderSuspendedProject() {
        AppEntityMapper apps = mock(AppEntityMapper.class);
        ProjectMapper projects = mock(ProjectMapper.class);
        TenantMapper tenants = mock(TenantMapper.class);
        Tenant tenant = new Tenant();
        tenant.setId("tenant-active");
        tenant.setStatus("ACTIVE");
        Project project = new Project();
        project.setId("project-suspended");
        project.setTenantId("tenant-active");
        project.setStatus("SUSPENDED");
        when(tenants.selectById("tenant-active")).thenReturn(tenant);
        when(projects.selectById("project-suspended")).thenReturn(project);
        AppController controller = new AppController(
                apps, projects, tenants, mock(AuditService.class), mock(ResourceLinkageService.class));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.create(new AppController.Request(
                        "tenant-active", "project-suspended", "App", "Owner", "DEV")));

        assertEquals(409, error.getStatusCode().value());
        verify(apps, never()).insert(any(AppEntity.class));
    }
}
