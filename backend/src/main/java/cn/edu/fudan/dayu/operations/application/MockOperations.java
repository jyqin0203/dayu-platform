package cn.edu.fudan.dayu.operations.application;

import cn.edu.fudan.dayu.assetindex.api.AssetIndexCommandService;
import cn.edu.fudan.dayu.assetindex.api.AssetScanTaskService;
import cn.edu.fudan.dayu.catalog.api.CatalogQueryService;
import cn.edu.fudan.dayu.discovery.api.DiscoveryQueryService;
import cn.edu.fudan.dayu.download.api.DownloadAuditQueryService;
import cn.edu.fudan.dayu.identity.api.UserAdminService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** 骨架复用同一编排规则，注入的各业务 API 仍是 skeleton Mock，不声称真实数据集成。 */
@Service
@Profile("skeleton")
class MockOperations extends OperationsApplicationService {
    MockOperations(CatalogQueryService catalog, AssetIndexCommandService commands, AssetScanTaskService scans,
            DiscoveryQueryService discovery, DownloadAuditQueryService downloads, UserAdminService users) {
        super(catalog,commands,scans,discovery,downloads,users);
    }
}
