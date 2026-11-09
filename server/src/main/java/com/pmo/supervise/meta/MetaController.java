package com.pmo.supervise.meta;

import com.pmo.supervise.common.Actor;
import com.pmo.supervise.meta.dto.MetaOptions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 下拉元数据接口（TECHNICAL §5.5）：登录即可用，范围由 MetaService 按角色裁剪。
 */
@RestController
@RequestMapping("/api/meta")
public class MetaController {

    private final MetaService metaService;

    public MetaController(MetaService metaService) {
        this.metaService = metaService;
    }

    @GetMapping("/options")
    public MetaOptions options(Actor actor) {
        return metaService.load(actor);
    }
}
