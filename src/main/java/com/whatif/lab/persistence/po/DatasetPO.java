package com.whatif.lab.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 数据集版本。 */
@TableName("dataset")
public class DatasetPO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String domain;
    private String source;
    private Integer version;
    private String description;
    /** CREATED / IMPORTING / READY / FAILED */
    private String status;
    private Integer entityCount;
    private Integer eventCount;
    private Integer rawRowCount;
    private Integer skippedRowCount;
    /** 原始列 → 标准字段的映射（JSON 原文）。 */
    private String columnMapping;
    private Integer baselineRuleVersion;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getEntityCount() {
        return entityCount;
    }

    public void setEntityCount(Integer entityCount) {
        this.entityCount = entityCount;
    }

    public Integer getEventCount() {
        return eventCount;
    }

    public void setEventCount(Integer eventCount) {
        this.eventCount = eventCount;
    }

    public Integer getRawRowCount() {
        return rawRowCount;
    }

    public void setRawRowCount(Integer rawRowCount) {
        this.rawRowCount = rawRowCount;
    }

    public Integer getSkippedRowCount() {
        return skippedRowCount;
    }

    public void setSkippedRowCount(Integer skippedRowCount) {
        this.skippedRowCount = skippedRowCount;
    }

    public String getColumnMapping() {
        return columnMapping;
    }

    public void setColumnMapping(String columnMapping) {
        this.columnMapping = columnMapping;
    }

    public Integer getBaselineRuleVersion() {
        return baselineRuleVersion;
    }

    public void setBaselineRuleVersion(Integer baselineRuleVersion) {
        this.baselineRuleVersion = baselineRuleVersion;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
