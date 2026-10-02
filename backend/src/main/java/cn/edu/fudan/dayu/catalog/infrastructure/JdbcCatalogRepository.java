package cn.edu.fudan.dayu.catalog.infrastructure;

import cn.edu.fudan.dayu.catalog.api.*;
import cn.edu.fudan.dayu.catalog.application.CatalogRepository;
import cn.edu.fudan.dayu.shared.kernel.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** 参数化 JDBC 适配器：单次 LEFT JOIN 批量读取详情与模式，没有每产品附加查询。 */
@Repository
@Profile("!skeleton")
public class JdbcCatalogRepository implements CatalogRepository {
    private static final String SELECT = "SELECT p.*, m.data_mode, m.enabled, m.stale_after_minutes "
            + "FROM products p LEFT JOIN product_mode_policies m ON m.product_id=p.id ";
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcCatalogRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override public List<ProductDetail> findAll(ManagedProductQuery q) {
        StringBuilder where = new StringBuilder("WHERE 1=1");
        MapSqlParameterSource args = new MapSqlParameterSource();
        if (q != null) {
            if (q.family() != null) { where.append(" AND p.family=:family"); args.addValue("family", q.family()); }
            if (q.status() != null) { where.append(" AND p.status=:status"); args.addValue("status", q.status().name()); }
            if (q.code() != null) { where.append(" AND p.code=:code"); args.addValue("code", q.code().value()); }
        }
        return read(SELECT + where + " ORDER BY p.sort_order,p.id,m.data_mode", args);
    }
    @Override public Optional<ProductDetail> findByCode(ProductCode code) {
        return read(SELECT + "WHERE p.code=:code ORDER BY m.data_mode",
                new MapSqlParameterSource("code", code.value())).stream().findFirst();
    }
    @Override public Optional<ProductDetail> lock(ProductId id) {
        // 锁父行再读子表：所有产品变更均遵循此顺序，含发布与最后模式检查。
        List<Long> ids = jdbc.queryForList("SELECT id FROM products WHERE id=:id FOR UPDATE",
                Map.of("id", id.value()), Long.class);
        if (ids.isEmpty()) return Optional.empty();
        return read(SELECT + "WHERE p.id=:id ORDER BY m.data_mode FOR UPDATE",
                new MapSqlParameterSource("id", id.value())).stream().findFirst();
    }

    @Override public ProductId insert(CreateProductCommand c, UserId actor, Instant now) {
        MapSqlParameterSource p = fields(c.nameZh(), c.nameEn(), c.unit(), c.descriptionZh(), c.descriptionEn(),
                c.producer(), c.algorithmName(), c.sourceDescription(), c.officialSourceUrl(), c.colorbarRequired(),
                c.colorbarPath(), c.sortOrder(), actor, now)
                .addValue("code", c.code().value()).addValue("family", c.family());
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        try {
            jdbc.update("""
                INSERT INTO products(code,family,name_zh,name_en,unit,description_zh,description_en,
                producer,algorithm_name,source_description,official_source_url,colorbar_required,colorbar_path,
                sort_order,status,created_by,updated_by,created_at,updated_at)
                VALUES(:code,:family,:zh,:en,:unit,:dz,:de,:producer,:algorithm,:source,:url,:required,:path,
                :sort,'DRAFT',:actor,:actor,:now,:now)
                """, p, key, new String[]{"id"});
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.CONFLICT, "产品编码已存在");
        }
        return new ProductId(Objects.requireNonNull(key.getKey()).longValue());
    }
    @Override public void update(ProductDetail d, UserId actor) {
        ProductSummary s = d.summary();
        MapSqlParameterSource p = fields(s.nameZh(), s.nameEn(), s.unit(), d.descriptionZh(), d.descriptionEn(),
                s.producer(), s.algorithmName(), s.sourceDescription(), s.officialSourceUrl(), d.colorbarRequired(),
                d.colorbarPath(), s.sortOrder(), actor, d.updatedAt()).addValue("id", s.id().value())
                .addValue("status", s.status().name()).addValue("published", utc(d.publishedAt()));
        jdbc.update("""
                UPDATE products SET name_zh=:zh,name_en=:en,unit=:unit,description_zh=:dz,description_en=:de,
                producer=:producer,algorithm_name=:algorithm,source_description=:source,official_source_url=:url,
                colorbar_required=:required,colorbar_path=:path,sort_order=:sort,status=:status,
                published_at=:published,updated_by=:actor,updated_at=:now WHERE id=:id
                """, p);
    }
    @Override public void saveMode(ProductId id, ProductModePolicy p, Instant now) {
        jdbc.update("""
                INSERT INTO product_mode_policies(product_id,data_mode,enabled,stale_after_minutes,created_at,updated_at)
                VALUES(:id,:mode,:enabled,:stale,:now,:now)
                ON DUPLICATE KEY UPDATE enabled=VALUES(enabled),stale_after_minutes=VALUES(stale_after_minutes),
                updated_at=VALUES(updated_at)
                """, new MapSqlParameterSource("id", id.value()).addValue("mode", p.dataMode().name())
                .addValue("enabled", p.enabled()).addValue("stale", p.staleAfter().toMinutes()).addValue("now", utc(now)));
    }
    @Override public void audit(ProductDetail before, ProductDetail after, String action, UserId actor, Instant now) {
        try {
            jdbc.update("""
                    INSERT INTO product_admin_events(product_id,admin_user_id,action,before_json,after_json,created_at)
                    VALUES(:id,:actor,:action,:before,:after,:now)
                    """, new MapSqlParameterSource("id", after.summary().id().value()).addValue("actor", actor.value())
                    .addValue("action", action).addValue("before", before == null ? null : json.writeValueAsString(before))
                    .addValue("after", json.writeValueAsString(after)).addValue("now", utc(now)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize catalog audit", e);
        }
    }
    private List<ProductDetail> read(String sql, MapSqlParameterSource args) {
        return jdbc.query(sql, args, rs -> {
            Map<Long, ProductDetail> products = new LinkedHashMap<>();
            Map<Long, List<ProductModePolicy>> modes = new LinkedHashMap<>();
            while (rs.next()) {
                long id = rs.getLong("id");
                if (!products.containsKey(id)) { products.put(id, detail(rs)); modes.put(id, new ArrayList<>()); }
                String mode = rs.getString("data_mode");
                if (mode != null) modes.get(id).add(new ProductModePolicy(products.get(id).summary().code(),
                        DataMode.valueOf(mode), rs.getBoolean("enabled"), Duration.ofMinutes(rs.getInt("stale_after_minutes"))));
            }
            return products.entrySet().stream().map(e -> {
                ProductDetail d = e.getValue();
                return new ProductDetail(d.summary(), d.descriptionZh(), d.descriptionEn(), d.colorbarRequired(),
                        d.colorbarPath(), modes.get(e.getKey()), d.publishedAt(), d.createdAt(), d.updatedAt());
            }).toList();
        });
    }
    private static ProductDetail detail(ResultSet r) throws SQLException {
        String url = r.getString("official_source_url");
        ProductSummary s = new ProductSummary(new ProductId(r.getLong("id")), new ProductCode(r.getString("code")),
                r.getString("name_zh"), r.getString("name_en"), r.getString("family"), r.getString("unit"),
                r.getString("producer"), r.getString("algorithm_name"), r.getString("source_description"),
                url == null ? null : URI.create(url), ProductStatus.valueOf(r.getString("status")), r.getInt("sort_order"));
        return new ProductDetail(s, r.getString("description_zh"), r.getString("description_en"),
                r.getBoolean("colorbar_required"), r.getString("colorbar_path"), List.of(),
                instant(r, "published_at"), instant(r, "created_at"), instant(r, "updated_at"));
    }
    private static MapSqlParameterSource fields(String zh, String en, String unit, String dz, String de,
            String producer, String algorithm, String source, URI url, boolean required, String path,
            int sort, UserId actor, Instant now) {
        return new MapSqlParameterSource("zh", zh).addValue("en", en).addValue("unit", unit)
                .addValue("dz", dz).addValue("de", de).addValue("producer", producer).addValue("algorithm", algorithm)
                .addValue("source", source).addValue("url", url == null ? null : url.toString())
                .addValue("required", required).addValue("path", path).addValue("sort", sort)
                .addValue("actor", actor.value()).addValue("now", utc(now));
    }
    private static LocalDateTime utc(Instant time) { return time == null ? null : LocalDateTime.ofInstant(time, ZoneOffset.UTC); }
    private static Instant instant(ResultSet r, String col) throws SQLException {
        LocalDateTime value = r.getObject(col, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
