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
@RequestMapping("/api/v1/workspaces/{workspaceId}/candidates")
public class CandidateController {

    private final CandidateService candidates;

    public CandidateController(CandidateService candidates) {
        this.candidates = candidates;
    }

    @PostMapping(value = "/resumes", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    CandidateService.CandidateDetail upload(@PathVariable UUID workspaceId,
                                            @RequestPart("file") MultipartFile file,
                                            @RequestParam(defaultValue = "NORMAL") String scenario,
                                            Authentication authentication) {
        return candidates.upload(CurrentUser.id(authentication), workspaceId, file, scenario);
    }

    @GetMapping
    CandidateService.CandidateListResult list(@PathVariable UUID workspaceId,
                                              @RequestParam(required = false) String search,
                                              @RequestParam(required = false) String status,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "20") int pageSize,
                                              Authentication authentication) {
        return candidates.list(CurrentUser.id(authentication), workspaceId, search, status, page, pageSize);
    }

    @GetMapping("/{candidateId}")
    CandidateService.CandidateDetail get(@PathVariable UUID workspaceId, @PathVariable UUID candidateId,
                                         Authentication authentication) {
        return candidates.get(CurrentUser.id(authentication), workspaceId, candidateId);
    }

    @PostMapping("/{candidateId}/reveal")
    CandidateService.RevealedPii reveal(@PathVariable UUID workspaceId, @PathVariable UUID candidateId,
                                        Authentication authentication) {
        return candidates.reveal(CurrentUser.id(authentication), workspaceId, candidateId);
    }

    @PostMapping("/{candidateId}/parse-retries")
    CandidateService.CandidateDetail retry(@PathVariable UUID workspaceId, @PathVariable UUID candidateId,
                                           @RequestBody(required = false) ParseRequest request,
                                           Authentication authentication) {
        return candidates.retryParse(CurrentUser.id(authentication), workspaceId, candidateId,
                request == null ? "NORMAL" : request.scenario());
    }

    @GetMapping("/{candidateId}/resume-file")
    ResponseEntity<byte[]> download(@PathVariable UUID workspaceId, @PathVariable UUID candidateId,
                                    Authentication authentication) {
        CandidateService.DownloadedResume file = candidates.download(CurrentUser.id(authentication), workspaceId, candidateId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .body(file.content());
    }

    @DeleteMapping("/{candidateId}")
    ResponseEntity<Void> delete(@PathVariable UUID workspaceId, @PathVariable UUID candidateId,
                                Authentication authentication) {
        candidates.delete(CurrentUser.id(authentication), workspaceId, candidateId);
        return ResponseEntity.noContent().build();
    }

    public record ParseRequest(String scenario) { }
}
