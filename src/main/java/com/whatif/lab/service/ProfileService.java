package com.whatif.lab.service;

import com.whatif.lab.common.BizException;
import com.whatif.lab.common.JsonCodec;
import com.whatif.lab.data.profile.CsvProfiler;
import com.whatif.lab.data.profile.DataProfile;
import com.whatif.lab.persistence.mapper.DatasetProfileMapper;
import com.whatif.lab.persistence.po.DatasetProfilePO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数据画像服务 —— 算 + 存 + 回放。
 *
 * <p><b>为什么必须"存"</b>：画像会被 AI 当作唯一输入。如果只算不存，
 * 出现"同一份 CSV 两次判出不同结果"时就没有任何办法复盘（是模型变了？文件变了？口径变了？）。
 * 存下来 + 带 {@code profileVersion} 与 {@code fileHash}，这三种原因才区分得开。
 *
 * <p><b>为什么暴露"回放"接口</b>：验收脚本要能拿到"当时那份画像"逐条对账，
 * 而不是靠重新算一遍（那只能证明"现在算得一样"，证明不了"当时算的是什么"）。
 */
@Service
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

    private final CsvProfiler profiler;
    private final DatasetProfileMapper mapper;
    private final JsonCodec json;

    public ProfileService(CsvProfiler profiler, DatasetProfileMapper mapper, JsonCodec json) {
        this.profiler = profiler;
        this.mapper = mapper;
        this.json = json;
    }

    /** 分析一份 CSV（导入之前）：算画像 + 落库 + 返回。 */
    public DataProfile analyze(String path, Integer statsRowLimit) {
        Path file = Path.of(path);
        if (!Files.isRegularFile(file)) {
            throw BizException.validation("文件不存在或不是普通文件: " + path);
        }
        long start = System.currentTimeMillis();
        DataProfile profile;
        try {
            profile = statsRowLimit == null
                    ? profiler.profile(file)
                    : profiler.profile(file, statsRowLimit);
        } catch (IOException e) {
            throw BizException.validation("画像失败: " + e.getMessage());
        }
        DatasetProfilePO po = new DatasetProfilePO();
        po.setProfileId(profile.profileId());
        po.setFileName(profile.fileName());
        po.setSourcePath(file.toAbsolutePath().normalize().toString());
        po.setFileHash(profile.fileHash16());
        po.setSizeBytes(profile.sizeBytes());
        po.setRowCount(profile.rowCount());
        po.setColumnCount(profile.columnCount());
        po.setCharset(profile.charset());
        po.setProfileVersion(profile.profileVersion());
        po.setProfileJson(json.write(profile));
        mapper.insert(po);
        log.info("画像完成 file={} rows={} cols={} charset={} 用时 {}ms",
                profile.fileName(), profile.rowCount(), profile.columnCount(),
                profile.charset(), System.currentTimeMillis() - start);
        return profile;
    }

    /** 取回落库的画像 + 源文件路径（判列要用原文件做数值冲突检测）。 */
    public Map<String, Object> raw(String profileId) {
        DatasetProfilePO po = mapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<DatasetProfilePO>()
                        .eq("profile_id", profileId));
        if (po == null) {
            throw new BizException(1002, "画像不存在：" + profileId);
        }
        DataProfile profile = json.read(po.getProfileJson(), DataProfile.class);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("profile", profile);
        out.put("sourcePath", po.getSourcePath());
        return out;
    }

    /** 回放一份历史画像（验收与排查用）。 */
    public Map<String, Object> replay(String profileId) {
        DatasetProfilePO po = mapper.selectOne(new QueryWrapper<DatasetProfilePO>()
                .eq("profile_id", profileId).last("limit 1"));
        if (po == null) {
            throw BizException.validation("找不到画像: " + profileId);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("profileId", po.getProfileId());
        out.put("fileName", po.getFileName());
        out.put("fileHash", po.getFileHash());
        out.put("rowCount", po.getRowCount());
        out.put("columnCount", po.getColumnCount());
        out.put("charset", po.getCharset());
        out.put("profileVersion", po.getProfileVersion());
        out.put("createdAt", String.valueOf(po.getCreatedAt()));
        out.put("profile", json.readMap(po.getProfileJson()));
        return out;
    }
}
