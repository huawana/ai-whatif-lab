package com.whatif.lab.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 数据画像落库 —— 为什么要存：AI Schema Mapper 的输入必须可追溯。
 *
 * <p>如果画像只算不存，"AI 当时看到的是什么"就查不到了；同一个 CSV 两次判出不同结果时，
 * 你无法区分是模型不稳定、还是输入变了、还是统计口径改了。所以画像带
 * {@code file_hash} + {@code profile_version} 一起落库。
 *
 * <p>{@code dataset_id} 允许为空：画像是**导入之前**的动作（先看清单子长什么样，再决定怎么导）。
 * 导入完成后可以回填，形成"原始文件 → 画像 → 配置 → 数据集 → 模型 → 实验"的完整链路。
 */
@TableName("dataset_profile")
public class DatasetProfilePO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String profileId;
    private Long datasetId;
    private String fileName;

    /** 源文件绝对路径 —— 判列时要用原始文件做"数量×单价 vs 金额"的抽样冲突检测。 */
    private String sourcePath;
    private String fileHash;
    private Long sizeBytes;
    private Long rowCount;
    private Integer columnCount;
    private String charset;
    private String profileVersion;
    private String profileJson;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProfileId() { return profileId; }
    public void setProfileId(String profileId) { this.profileId = profileId; }
    public Long getDatasetId() { return datasetId; }
    public void setDatasetId(Long datasetId) { this.datasetId = datasetId; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getFileHash() { return fileHash; }
    public void setFileHash(String fileHash) { this.fileHash = fileHash; }

    public String getSourcePath() { return sourcePath; }

    public void setSourcePath(String sourcePath) { this.sourcePath = sourcePath; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public Long getRowCount() { return rowCount; }
    public void setRowCount(Long rowCount) { this.rowCount = rowCount; }
    public Integer getColumnCount() { return columnCount; }
    public void setColumnCount(Integer columnCount) { this.columnCount = columnCount; }
    public String getCharset() { return charset; }
    public void setCharset(String charset) { this.charset = charset; }
    public String getProfileVersion() { return profileVersion; }
    public void setProfileVersion(String profileVersion) { this.profileVersion = profileVersion; }
    public String getProfileJson() { return profileJson; }
    public void setProfileJson(String profileJson) { this.profileJson = profileJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
