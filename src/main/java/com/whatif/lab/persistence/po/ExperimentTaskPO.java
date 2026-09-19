package com.whatif.lab.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 实验任务（状态机 + 幂等键 + 重试计数）。
 *
 * <p>为什么任务要独立成表而不是复用 {@code experiment.status}：
 * 「实验」是业务对象（有结果、可查询），「任务」是执行载体（可重试、有 worker、有超时）。
 * 两者生命周期不同 —— 一次实验可能被投递两次（Kafka 至少一次语义），
 * 但只允许执行一次；把幂等键放在任务表上，experiment_id 唯一索引就是那道人肉防线。
 */
@TableName("experiment_task")
public class ExperimentTaskPO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long experimentId;
    /** PENDING / RUNNING / SUCCESS / FAILED */
    private String status;
    private Integer retryCount;
    private Integer maxRetry;
    private String worker;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getExperimentId() {
        return experimentId;
    }

    public void setExperimentId(Long experimentId) {
        this.experimentId = experimentId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public Integer getMaxRetry() {
        return maxRetry;
    }

    public void setMaxRetry(Integer maxRetry) {
        this.maxRetry = maxRetry;
    }

    public String getWorker() {
        return worker;
    }

    public void setWorker(String worker) {
        this.worker = worker;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
