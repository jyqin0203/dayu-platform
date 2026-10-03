package cn.edu.fudan.dayu.assetindex.domain;

import cn.edu.fudan.dayu.shared.kernel.AssetType;
import cn.edu.fudan.dayu.shared.kernel.DataMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.regex.Pattern;

/** 严格解析已确认 FY4B 正式命名；目录与文件名中的时间必须一致。 */
public final class AssetFilenameParser {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuuMMddHHmm")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern WEBP = Pattern.compile(
            "FY4B_AGRI_([A-Z][A-Z0-9]*)(?:_FD)?_(\\d{12})(?:_(\\d{12}))?(?:_Dpi(\\d+))?\\.webp");
    private static final Pattern NC = Pattern.compile(
            "FY4B_AGRI_(REPPIC_PRECIP)(?:_([0-9]+(?:\\.[0-9]+)?)H)?_(\\d{12})(?:_(\\d{12}))?\\.nc");
    private static final Pattern REPPIC_WEBP = Pattern.compile(
            "FY4B_AGRI_REPPIC_PRECIP_([123])H_(\\d{12})_(\\d{12})_palettev2_Dpi500\\.webp");

    public ParsedAsset parse(String relativePath, AssetType type, int configuredDpi) {
        validateRelativePath(relativePath);
        String[] parts = relativePath.split("/");
        boolean forecast = parts[0].equals("forecast");
        if (!forecast && !parts[0].equals("realtime")) throw invalid();
        int expected = type == AssetType.WEBP ? (forecast ? 4 : 3) : (forecast ? 3 : 2);
        if (parts.length != expected) throw invalid();
        var reppic=REPPIC_WEBP.matcher(parts[parts.length-1]);
        if (type==AssetType.WEBP && reppic.matches()) {
            int hours=Integer.parseInt(reppic.group(1));
            if (!forecast || configuredDpi!=500 || !parts[2].equals("PRECIP_"+hours+"H")) throw invalid();
            Instant cycle=parseTime(reppic.group(2)), valid=parseTime(reppic.group(3));
            if (!parts[1].equals(reppic.group(2)) || !valid.equals(cycle.plusSeconds(hours*3600L))) throw invalid();
            // Physical directory names are delivery aliases, not additional Catalog products.
            return new ParsedAsset(type,DataMode.FORECAST,null,"PRECIP",cycle,valid,hours*60,500,true);
        }
        var matcher = (type == AssetType.WEBP ? WEBP : NC).matcher(parts[parts.length - 1]);
        if (!matcher.matches()) throw invalid();
        String product = type == AssetType.WEBP ? matcher.group(1) : null;
        if (product != null && !product.equals(parts[parts.length - 2])) throw invalid();
        String first = matcher.group(type == AssetType.WEBP ? 2 : 3);
        String second = matcher.group(type == AssetType.WEBP ? 3 : 4);
        Instant time = parseTime(first);
        Instant cycle = forecast ? parseTime(parts[1]) : null;
        Instant valid = second == null ? time : parseTime(second);
        if (forecast != (second != null) || forecast && !cycle.equals(time)) throw invalid();
        long lead = forecast ? Duration.between(cycle, valid).toMinutes() : 0;
        if (lead < 0 || lead > Integer.MAX_VALUE) throw invalid();
        Integer dpi = null;
        if (type == AssetType.WEBP) {
            // Draw_FD 的 RGB/FRGB 省略文件名 DPI；它们仍属于已配置的唯一 DPI 根。
            if (matcher.group(4)==null && !product.equals("RGB") && !product.equals("FRGB")) throw invalid();
            dpi = matcher.group(4)==null ? configuredDpi : Integer.valueOf(matcher.group(4));
        }
        if (dpi != null && dpi != configuredDpi) throw invalid();
        if (type == AssetType.NETCDF && forecast && matcher.group(2) == null) throw invalid();
        if (type == AssetType.NETCDF && matcher.group(2) != null) {
            var declared = new java.math.BigDecimal(matcher.group(2)).multiply(java.math.BigDecimal.valueOf(60));
            if (!forecast || declared.intValueExact() != lead) throw invalid();
        }
        return new ParsedAsset(type, forecast ? DataMode.FORECAST : DataMode.REALTIME,
                type == AssetType.NETCDF ? matcher.group(1) : null, product, cycle, valid,
                forecast ? (int) lead : null, dpi,
                type==AssetType.WEBP && forecast && "PRECIP".equals(product));
    }

    /** 不接受绝对路径、编码逃逸、控制字符及空路径段。 */
    public static void validateRelativePath(String path) {
        if (path == null || path.isBlank() || path.length() > 700 || path.startsWith("/")
                || path.contains("\\") || path.contains(":") || path.contains("%")
                || path.chars().anyMatch(c -> c < 32 || c == 127)) throw invalid();
        for (String part : path.split("/", -1)) {
            if (part.isBlank() || part.equals(".") || part.equals("..")) throw invalid();
        }
    }

    public static Instant parseTime(String text) {
        return LocalDateTime.parse(text, TIME).toInstant(ZoneOffset.UTC);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid published asset name or path");
    }
}
