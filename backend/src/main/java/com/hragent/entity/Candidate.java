package com.hragent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("candidate")
public class Candidate {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String resumeId;

    private String name;

    /** 在线简历快照(结构化字段 JSON) */
    private String snapshot;

    private Integer score;

    private String passStatus;

    /** 招聘跟进状态(HR 工作流):PENDING_REVIEW/QUALIFIED/INTERVIEW_SCHEDULED/NOT_SUITABLE */
    private String recruitStatus;

    /** 简历最后查看时间(NULL=未查看) */
    private LocalDateTime resumeLastViewedAt;

    /** 简历最后查看人(sys_user.id) */
    private Long resumeLastViewedBy;

    private Long jdId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
