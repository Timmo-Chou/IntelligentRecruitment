package com.intelligentrecruitment.candidates.api;

import com.intelligentrecruitment.candidates.application.CandidateService;
import com.intelligentrecruitment.shared.security.CurrentUser;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/candidates")
public class CandidateController {

    private final CandidateService candidates;

    public CandidateController(CandidateService candidates) {
        this.candidates = candidates;
    }

    @PostMapping(value = "/resumes", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    CandidateService.CandidateDetail upload(@PathVariable UUID companyId,
                                            @RequestPart("file") MultipartFile file,
                                            Authentication authentication) {
        return candidates.upload(CurrentUser.id(authentication), companyId, file);
    }

    @PostMapping
    CandidateService.CandidateDetail create(@PathVariable UUID companyId,
                                            @RequestBody CandidateService.ManualTalentInput input,
                                            Authentication authentication) {
        return candidates.createManual(CurrentUser.id(authentication), companyId, input);
    }

    @GetMapping("/stats")
    CandidateService.CandidateStats stats(@PathVariable UUID companyId, Authentication authentication) {
        return candidates.stats(CurrentUser.id(authentication), companyId);
    }

    @GetMapping
    CandidateService.CandidateListResult list(@PathVariable UUID companyId,
                                              @RequestParam(required = false) String search,
                                              @RequestParam(required = false) String status,
                                              @RequestParam(required = false) String segment,
                                              @RequestParam(required = false) Integer minMatchScore,
                                              @RequestParam(required = false) String industry,
                                              @RequestParam(required = false) String city,
                                              @RequestParam(required = false) String tags,
                                              @RequestParam(required = false) Integer yearsMin,
                                              @RequestParam(required = false) Integer yearsMax,
                                              @RequestParam(required = false) String education,
                                              @RequestParam(required = false) String source,
                                              @RequestParam(required = false) String activity,
                                              @RequestParam(required = false) String talentStatus,
                                              @RequestParam(required = false) String createdFrom,
                                              @RequestParam(required = false) String createdTo,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "20") int pageSize,
                                              Authentication authentication) {
        return candidates.list(CurrentUser.id(authentication), companyId,
                new CandidateService.CandidateListQuery(
                        search, status, segment, minMatchScore, industry, city, tags, yearsMin, yearsMax,
                        education, source, activity, talentStatus, createdFrom, createdTo, page, pageSize));
    }

    @GetMapping("/{candidateId}")
    CandidateService.CandidateDetail get(@PathVariable UUID companyId, @PathVariable UUID candidateId,
                                         Authentication authentication) {
        return candidates.get(CurrentUser.id(authentication), companyId, candidateId);
    }

    @PostMapping("/{candidateId}/reveal")
    CandidateService.RevealedPii reveal(@PathVariable UUID companyId, @PathVariable UUID candidateId,
                                        Authentication authentication) {
        return candidates.reveal(CurrentUser.id(authentication), companyId, candidateId);
    }

    @PostMapping("/{candidateId}/parse-retries")
    CandidateService.CandidateDetail retry(@PathVariable UUID companyId, @PathVariable UUID candidateId,
                                           Authentication authentication) {
        return candidates.retryParse(CurrentUser.id(authentication), companyId, candidateId);
    }

    @GetMapping("/{candidateId}/resume-file")
    ResponseEntity<byte[]> download(@PathVariable UUID companyId, @PathVariable UUID candidateId,
                                    Authentication authentication) {
        CandidateService.DownloadedResume file = candidates.download(CurrentUser.id(authentication), companyId, candidateId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .body(file.content());
    }

    @PatchMapping("/{candidateId}/tags")
    CandidateService.CandidateDetail updateTags(@PathVariable UUID companyId,
                                                @PathVariable UUID candidateId,
                                                @RequestBody TagsUpdateRequest request,
                                                Authentication authentication) {
        return candidates.updateTags(CurrentUser.id(authentication), companyId, candidateId,
                request == null ? java.util.List.of() : request.tags());
    }

    @DeleteMapping("/{candidateId}")
    ResponseEntity<Void> delete(@PathVariable UUID companyId, @PathVariable UUID candidateId,
                                Authentication authentication) {
        candidates.delete(CurrentUser.id(authentication), companyId, candidateId);
        return ResponseEntity.noContent().build();
    }

    public record TagsUpdateRequest(java.util.List<String> tags) { }
}
