package com.intelligentrecruitment.candidates.application;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class CandidateStructuredResumeTest {
 @Test void nullableFieldsAndExperienceObjectsArePreserved(){Map<String,Object> source=new LinkedHashMap<>();source.put("summary",null);source.put("work_experience",List.of(Map.of("company","Synthetic","position","Engineer")));assertThat(CandidateService.copyNullableObject(source)).containsEntry("summary",null).containsEntry("work_experience",source.get("work_experience"));}
 @Test void confirmedFactsAndUserTagsSurviveReparse(){Map<String,Object> existing=new LinkedHashMap<>(Map.of("manualConfirmed",true,"yearsExperience",new java.math.BigDecimal("1.5"),"tags",List.of("confirmed")));assertThat(CandidateService.mergeParsedProfile(Map.of("yearsExperience",9,"tags",List.of()),existing)).containsEntry("yearsExperience",new java.math.BigDecimal("1.5")).containsEntry("tags",List.of("confirmed"));assertThat(CandidateService.mergeParsedProfile(Map.of("skills",List.of("Java"),"tags",List.of()),Map.of("tags",List.of("manual")))).containsEntry("tags",List.of("manual")).containsEntry("skills",List.of("Java"));}
}
