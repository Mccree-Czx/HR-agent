package com.hragent.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hragent.common.ApiResponse;
import com.hragent.common.BizException;
import com.hragent.dto.CandidateLedger;
import com.hragent.entity.Candidate;
import com.hragent.entity.GreetingRecord;
import com.hragent.entity.Jd;
import com.hragent.entity.ResumeFile;
import com.hragent.entity.ScoreRecord;
import com.hragent.repository.CandidateMapper;
import com.hragent.repository.GreetingRecordMapper;
import com.hragent.repository.JdMapper;
import com.hragent.repository.ResumeFileMapper;
import com.hragent.repository.ScoreRecordMapper;
import com.hragent.security.LoginUser;
import com.hragent.security.UserContext;
import com.hragent.service.UserJdService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 候选人台账:全流程状态可见(搜索→评分→打招呼→简历入库) */
@RestController
@RequestMapping("/api/candidate")
public class CandidateController {

    private final CandidateMapper candidateMapper;
    private final ScoreRecordMapper scoreRecordMapper;
    private final GreetingRecordMapper greetingMapper;
    private final ResumeFileMapper resumeFileMapper;
    private final JdMapper jdMapper;
    private final UserJdService userJdService;

    public CandidateController(CandidateMapper candidateMapper, ScoreRecordMapper scoreRecordMapper,
                               GreetingRecordMapper greetingMapper, ResumeFileMapper resumeFileMapper,
                               JdMapper jdMapper, UserJdService userJdService) {
        this.candidateMapper = candidateMapper;
        this.scoreRecordMapper = scoreRecordMapper;
        this.greetingMapper = greetingMapper;
        this.resumeFileMapper = resumeFileMapper;
        this.jdMapper = jdMapper;
        this.userJdService = userJdService;
    }

    @GetMapping
    public ApiResponse<IPage<CandidateLedger>> page(@RequestParam(defaultValue = "1") int pageNo,
                                                    @RequestParam(defaultValue = "10") int pageSize,
                                                    @RequestParam(required = false) Long jdId,
                                                    @RequestParam(required = false) String passStatus,
                                                    @RequestParam(required = false) Boolean hasResumeFile,
                                                    @RequestParam(required = false) Boolean unassigned) {
        LoginUser user = UserContext.get();
        Set<Long> allowed = userJdService.allowedJdIds(user.getUserId(), user.getRole());

        boolean onlyWithResume = Boolean.TRUE.equals(hasResumeFile);
        boolean onlyUnassigned = Boolean.TRUE.equals(unassigned);

        LambdaQueryWrapper<Candidate> qw = new LambdaQueryWrapper<Candidate>()
                .eq(jdId != null, Candidate::getJdId, jdId)
                .eq(passStatus != null && !passStatus.isBlank(), Candidate::getPassStatus, passStatus)
                .orderByDesc(Candidate::getId);

        // 已收简历视图:仅返回存在 resume_file 入库记录的候选人(设计 §3.4/§4.3)
        if (onlyWithResume) {
            Set<Long> withResume = resumeFileMapper.selectList(null).stream()
                    .map(ResumeFile::getCandidateId)
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toSet());
            if (withResume.isEmpty()) {
                return ApiResponse.ok(emptyLedgerPage(pageNo, pageSize));
            }
            qw.in(Candidate::getId, withResume);
        }

        // 待分配视图:jd_id 为空或指向已不存在的岗位(设计 §4.3)
        if (onlyUnassigned) {
            Set<Long> existingJdIds = jdMapper.selectList(null).stream()
                    .map(Jd::getId)
                    .collect(Collectors.toSet());
            // jd 表为空时,任何非空 jd_id 均视为失效 → 仅按 isNull / isNotNull 组合
            qw.and(w -> w.isNull(Candidate::getJdId)
                    .or(inner -> inner.isNotNull(Candidate::getJdId)
                            .notIn(!existingJdIds.isEmpty(), Candidate::getJdId, existingJdIds)));
        }

        // 岗位权限过滤;待分配视图中的候选人无有效岗位,无法按岗位授权,故跳过
        if (allowed != null && !onlyUnassigned) {
            if (allowed.isEmpty()) {
                return ApiResponse.ok(emptyLedgerPage(pageNo, pageSize));
            }
            qw.in(Candidate::getJdId, allowed);
        }

        Page<Candidate> page = candidateMapper.selectPage(new Page<>(pageNo, pageSize), qw);
        List<CandidateLedger> ledgers = assembleLedgers(page.getRecords());

        Page<CandidateLedger> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(ledgers);
        return ApiResponse.ok(result);
    }

    /** 空结果分页(权限不可见/过滤无命中时 total=0) */
    private Page<CandidateLedger> emptyLedgerPage(int pageNo, int pageSize) {
        Page<CandidateLedger> empty = new Page<>(pageNo, pageSize);
        empty.setRecords(new ArrayList<>());
        empty.setTotal(0);
        return empty;
    }

    /** 组装台账:批量取最新评分/打招呼/简历文件 */
    private List<CandidateLedger> assembleLedgers(List<Candidate> candidates) {
        if (candidates.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> ids = candidates.stream().map(Candidate::getId).toList();

        Map<Long, ScoreRecord> scores = scoreRecordMapper.selectList(
                        new LambdaQueryWrapper<ScoreRecord>().in(ScoreRecord::getCandidateId, ids))
                .stream().collect(Collectors.toMap(ScoreRecord::getCandidateId, Function.identity(),
                        (a, b) -> a.getId() > b.getId() ? a : b));
        Map<Long, GreetingRecord> greetings = greetingMapper.selectList(
                        new LambdaQueryWrapper<GreetingRecord>().in(GreetingRecord::getCandidateId, ids))
                .stream().collect(Collectors.toMap(GreetingRecord::getCandidateId, Function.identity(), (a, b) -> a));
        Map<Long, ResumeFile> resumes = resumeFileMapper.selectList(
                        new LambdaQueryWrapper<ResumeFile>().in(ResumeFile::getCandidateId, ids))
                .stream().collect(Collectors.toMap(ResumeFile::getCandidateId, Function.identity(), (a, b) -> a));

        List<CandidateLedger> ledgers = new ArrayList<>();
        for (Candidate candidate : candidates) {
            ledgers.add(new CandidateLedger(
                    candidate,
                    scores.get(candidate.getId()),
                    greetings.get(candidate.getId()),
                    resumes.get(candidate.getId())));
        }
        return ledgers;
    }
}
