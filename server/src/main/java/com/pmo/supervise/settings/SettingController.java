package com.pmo.supervise.settings;

import com.pmo.supervise.common.Actor;
import com.pmo.supervise.settings.dto.ReportSettings;
import com.pmo.supervise.settings.dto.SettingsView;
import com.pmo.supervise.settings.dto.UpdateReportSettingsRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/settings（TECHNICAL §5.5）。权限判定在 SettingService（admin），
 * Controller 只做参数绑定。PUT 返回保存后的 report 段：页面保存后仍会重新 GET，
 * 但接口自身也要有可校验的响应体（契约 200 + 结果），避免“保存成功与否只能靠刷新猜”。
 */
@RestController
@RequestMapping("/api/settings")
public class SettingController {

    private final SettingService settingService;

    public SettingController(SettingService settingService) {
        this.settingService = settingService;
    }

    @GetMapping
    public SettingsView get(Actor actor) {
        return settingService.view(actor);
    }

    @PutMapping("/report")
    public ReportSettings updateReport(@Valid @RequestBody UpdateReportSettingsRequest request, Actor actor,
                                       HttpServletRequest httpRequest) {
        return settingService.updateReport(actor, request, httpRequest);
    }
}
