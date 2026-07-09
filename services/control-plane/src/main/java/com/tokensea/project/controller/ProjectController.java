package com.tokensea.project.controller;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokensea.common.BaseCrudController;
import com.tokensea.project.entity.Project;
import com.tokensea.project.mapper.ProjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
public class ProjectController extends BaseCrudController<Project> {
    private final ProjectMapper mapper;
    public ProjectController(ProjectMapper mapper) { this.mapper = mapper; }
    @Override protected BaseMapper<Project> mapper() { return mapper; }
}
