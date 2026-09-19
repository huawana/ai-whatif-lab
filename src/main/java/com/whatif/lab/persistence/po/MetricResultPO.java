package com.whatif.lab.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;

/** 指标明细（每指标一行 × 两臂）。 */
@TableName("metric_result")
public class MetricResultPO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long experimentId;
    private String arm;
    private String metric;
    private BigDecimal meanValue;
    private BigDecimal p5Value;
    private BigDecimal p50Value;
    private BigDecimal p95Value;
    private BigDecimal stdValue;

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

    public String getArm() {
        return arm;
    }

    public void setArm(String arm) {
        this.arm = arm;
    }

    public String getMetric() {
        return metric;
    }

    public void setMetric(String metric) {
        this.metric = metric;
    }

    public BigDecimal getMeanValue() {
        return meanValue;
    }

    public void setMeanValue(BigDecimal meanValue) {
        this.meanValue = meanValue;
    }

    public BigDecimal getP5Value() {
        return p5Value;
    }

    public void setP5Value(BigDecimal p5Value) {
        this.p5Value = p5Value;
    }

    public BigDecimal getP50Value() {
        return p50Value;
    }

    public void setP50Value(BigDecimal p50Value) {
        this.p50Value = p50Value;
    }

    public BigDecimal getP95Value() {
        return p95Value;
    }

    public void setP95Value(BigDecimal p95Value) {
        this.p95Value = p95Value;
    }

    public BigDecimal getStdValue() {
        return stdValue;
    }

    public void setStdValue(BigDecimal stdValue) {
        this.stdValue = stdValue;
    }
}
