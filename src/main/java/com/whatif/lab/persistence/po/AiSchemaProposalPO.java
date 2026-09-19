package com.whatif.lab.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * Schema 提案的落库记录。
 *
 * <p><b>为什么每次提案都要落库</b>：AI 判列最怕的不是判错，而是**判错了查不出来**。
 * 落库之后才能回答：当时用哪份画像、哪个模型、哪个提示词版本、判了什么、校验器报了啥。
 * 没有这张表，"AI 判列"就是一次性的、不可复盘的运气。
 */
@TableName("ai_schema_proposal")
public class AiSchemaProposalPO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String proposalId;
    private String profileId;
    private String fileHash;
    private String producer;
    private String model;
    private String promptVersion;
    private String declaredDomain;
    private String capability;
    private Boolean executable;
    private Integer openUnknowns;
    private Integer errorCount;
    private String proposalJson;
    private String validationJson;
    private Boolean confirmed;
    private String confirmedDomain;
    private LocalDateTime createdAt;

    public Long getId() { return id; }

    public void setId(Long id) { this.id = id; }

    public String getProposalId() { return proposalId; }

    public void setProposalId(String proposalId) { this.proposalId = proposalId; }

    public String getProfileId() { return profileId; }

    public void setProfileId(String profileId) { this.profileId = profileId; }

    public String getFileHash() { return fileHash; }

    public void setFileHash(String fileHash) { this.fileHash = fileHash; }

    public String getProducer() { return producer; }

    public void setProducer(String producer) { this.producer = producer; }

    public String getModel() { return model; }

    public void setModel(String model) { this.model = model; }

    public String getPromptVersion() { return promptVersion; }

    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }

    public String getDeclaredDomain() { return declaredDomain; }

    public void setDeclaredDomain(String declaredDomain) { this.declaredDomain = declaredDomain; }

    public String getCapability() { return capability; }

    public void setCapability(String capability) { this.capability = capability; }

    public Boolean getExecutable() { return executable; }

    public void setExecutable(Boolean executable) { this.executable = executable; }

    public Integer getOpenUnknowns() { return openUnknowns; }

    public void setOpenUnknowns(Integer openUnknowns) { this.openUnknowns = openUnknowns; }

    public Integer getErrorCount() { return errorCount; }

    public void setErrorCount(Integer errorCount) { this.errorCount = errorCount; }

    public String getProposalJson() { return proposalJson; }

    public void setProposalJson(String proposalJson) { this.proposalJson = proposalJson; }

    public String getValidationJson() { return validationJson; }

    public void setValidationJson(String validationJson) { this.validationJson = validationJson; }

    public Boolean getConfirmed() { return confirmed; }

    public void setConfirmed(Boolean confirmed) { this.confirmed = confirmed; }

    public String getConfirmedDomain() { return confirmedDomain; }

    public void setConfirmedDomain(String confirmedDomain) { this.confirmedDomain = confirmedDomain; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
