package cn.edu.fudan.dayu.shared.kernel;

/**
 * 表示一个 WebP 或 NetCDF 文件资产的唯一内部编号。
 * 使用专门类型，避免与用户 ID、产品 ID 等数字混淆。
 */
public record AssetId(long value) {
    public AssetId {
        if (value <= 0) throw new IllegalArgumentException("asset id must be positive");
    }
}
