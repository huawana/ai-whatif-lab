package com.whatif.lab.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 实验（所有参与计算的参数都固化在这里 → 可完整复现）。 */
@TableName("experiment")
public class ExperimentPO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long datasetId;
    private Long scenarioId;
    private Long behaviorModelId;
    private Integer baselineRuleVersion;
    private String scenarioDslHash;
    private String domain;
    private Integer durationDays;
    private Integer simulationCount;
    private Long randomSeed;
    private Integer activeCustomers;
    private Integer candidatesPerCustomer;
    private String status;
    private String stage;
    private Integer progress;
    private String cacheKey;
    private Boolean cacheHit;
    private Integer retryCount;
    private String errorMessage;
    private Long elapsedMs;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

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

    public Long getScenarioId() {
        return scenarioId;
    }

    public void setScenarioId(Long scenarioId) {
        this.scenarioId = scenarioId;
    }

    public Long getBehaviorModelId() {
        return behaviorModelId;
    }

    public void setBehaviorModelId(Long behaviorModelId) {
        this.behaviorModelId = behaviorModelId;
    }

    public Integer getBaselineRuleVersion() {
        return baselineRuleVersion;
    }

    public void setBaselineRuleVersion(Integer baselineRuleVersion) {
        this.baselineRuleVersion = baselineRuleVersion;
    }

    public String getScenarioDslHash() {
        return scenarioDslHash;
    }

    public void setScenarioDslHash(String scenarioDslHash) {
        this.scenarioDslHash = scenarioDslHash;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public Integer getDurationDays() {
        return durationDays;
    }

    public void setDurationDays(Integer durationDays) {
        this.durationDays = durationDays;
    }

    public Integer getSimulationCount() {
        return simulationCount;
    }

    public void setSimulationCount(Integer simulationCount) {
        this.simulationCount = simulationCount;
    }

    public Long getRandomSeed() {
        return randomSeed;
    }

    public void setRandomSeed(Long randomSeed) {
        this.randomSeed = randomSeed;
    }

    public Integer getActiveCustomers() {
        return activeCustomers;
    }

    public void setActiveCustomers(Integer activeCustomers) {
        this.activeCustomers = activeCustomers;
    }

    public Integer getCandidatesPerCustomer() {
        return candidatesPerCustomer;
    }

    public void setCandidatesPerCustomer(Integer candidatesPerCustomer) {
        this.candidatesPerCustomer = candidatesPerCustomer;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public Integer getProgress() {
        return progress;
    }

    public void setProgress(Integer progress) {
        this.progress = progress;
    }

    public String getCacheKey() {
        return cacheKey;
    }

    public void setCacheKey(String cacheKey) {
        this.cacheKey = cacheKey;
    }

    public Boolean getCacheHit() {
        return cacheHit;
    }

    public void setCacheHit(Boolean cacheHit) {
        this.cacheHit = cacheHit;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Long getElapsedMs() {
        return elapsedMs;
    }

    public void setElapsedMs(Long elapsedMs) {
        this.elapsedMs = elapsedMs;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }
}
