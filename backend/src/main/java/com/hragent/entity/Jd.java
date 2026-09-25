package com.hragent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("jd")
public class Jd {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    private String externalJd;

    private String internalNotes;

    private String city;

    private String district;

    /** 猎聘职位类别编码(如 N000330) */
    private String jobCategory;

    /** 经验要求文本(如 5-10年) */
    private String experienceReq;

    /** 学历要求文本(如 本科) */
    private String degreeReq;

    /** 薪资月数(如 13) */
    private Integer salaryMonths;

    private Integer salaryMin;

    private Integer salaryMax;

    private String status;

    /** NOT_PUBLISHED/PUBLISHING/PUBLISHED/FAILED */
    private String publishStatus;

    /** 猎聘职位 ID(发布后回填) */
    private String liepinJobId;

    private String publishError;

    /** LOCAL=系统创建 / SYNCED=猎聘同步 */
    private String source;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
