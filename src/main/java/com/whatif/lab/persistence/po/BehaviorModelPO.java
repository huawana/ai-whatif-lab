package com.whatif.lab.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 行为模型版本（逻辑回归权重 + 标准化参数）。 */
@TableName("behavior_model")
public class BehaviorModelPO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long datasetId;
    private Integer version;
    private String modelType;
    /** 特征名顺序（JSON 数组）。推理时按这个顺序拼向量 —— 顺序错了模型静默失效。 */
    private String featureNames;
    private String coefficients;
    private BigDecimal intercept;
    private String featureMeans;
    private String featureStds;
    private String trainMetrics;
    private Integer trainSampleCount;
    private Integer positiveCount;
    private Long trainingSeed;
    private LocalDateTime trainedAt;
    private Boolean active;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDatasetId() {
        return datasetId;
    }

    public void setDatasetId(Long datasetId) {
        this.datasetId = datasetId;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getModelType() {
        return modelType;
    }

    public void setModelType(String modelType) {
        this.modelType = modelType;
    }

    public String getFeatureNames() {
        return featureNames;
    }

    public void setFeatureNames(String featureNames) {
        this.featureNames = featureNames;
    }

    public String getCoefficients() {
        return coefficients;
    }

    public void setCoefficients(String coefficients) {
        this.coefficients = coefficients;
    }

    public BigDecimal getIntercept() {
        return intercept;
    }

    public void setIntercept(BigDecimal intercept) {
        this.intercept = intercept;
    }

    public String getFeatureMeans() {
        return featureMeans;
    }

    public void setFeatureMeans(String featureMeans) {
        this.featureMeans = featureMeans;
    }

    public String getFeatureStds() {
        return featureStds;
    }

    public void setFeatureStds(String featureStds) {
        this.featureStds = featureStds;
    }

    public String getTrainMetrics() {
        return trainMetrics;
    }

    public void setTrainMetrics(String trainMetrics) {
        this.trainMetrics = trainMetrics;
    }

    public Integer getTrainSampleCount() {
        return trainSampleCount;
    }

    public void setTrainSampleCount(Integer trainSampleCount) {
        this.trainSampleCount = trainSampleCount;
    }

    public Integer getPositiveCount() {
        return positiveCount;
    }

    public void setPositiveCount(Integer positiveCount) {
        this.positiveCount = positiveCount;
    }

    public Long getTrainingSeed() {
        return trainingSeed;
    }

    public void setTrainingSeed(Long trainingSeed) {
        this.trainingSeed = trainingSeed;
    }

    public LocalDateTime getTrainedAt() {
        return trainedAt;
    }

    public void setTrainedAt(LocalDateTime trainedAt) {
        this.trainedAt = trainedAt;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }
}
