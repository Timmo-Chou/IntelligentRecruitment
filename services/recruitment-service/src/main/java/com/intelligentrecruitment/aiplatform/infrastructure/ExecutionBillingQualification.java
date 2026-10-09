package com.intelligentrecruitment.aiplatform.infrastructure;
import com.intelligentrecruitment.agentflow.domain.StructuredResult;
import java.math.BigDecimal;
import java.util.*;
/** A cancellation changes business application, never the evidence-based billing eligibility. */
public final class ExecutionBillingQualification {
 private ExecutionBillingQualification(){}
 public record Decision(int units,String validity,String reason){}
 public static Decision evaluate(StructuredResult result){return evaluate(result,null,null);}

 public static Decision evaluate(StructuredResult result,String capability,String operation){
   if(result==null||result.data()==null||!Set.of(StructuredResult.Status.COMPLETED,StructuredResult.Status.DRAFT_READY).contains(result.status()))return null;
   var data=result.data();
   String capabilityCode=capability==null?"":capability.toLowerCase(Locale.ROOT);
   String operationCode=operation==null?"":operation.toLowerCase(Locale.ROOT);
   if(data.get("candidates") instanceof List<?> rows){
     if(rows.isEmpty()||rows.size()>5||!(data.get("batch") instanceof Map<?,?> batch)
             ||decimalInteger(batch.get("input_count"))!=rows.size()
             ||!(data.get("candidate_input_bindings") instanceof List<?> bindings)||bindings.size()!=rows.size())return null;
     if(rows.size()==1&&!batch.containsKey("failed_count")&&rows.getFirst() instanceof Map<?,?> single
             &&bindings.getFirst() instanceof Map<?,?> binding&&decimalInteger(binding.get("input_order"))==1
             &&binding.get("file_asset_id") instanceof String asset&&!asset.isBlank()
             &&binding.get("sha256") instanceof String sha&&!sha.isBlank()){
       RecruitmentMatchQualification.Decision d=RecruitmentMatchQualification.evaluate(single,1);
       return d==null?null:new Decision(d.units(),d.validity(),d.reason());
     }
     int units=0,failed=0,hard=0;java.util.Set<String> refs=new java.util.HashSet<>(),assets=new java.util.HashSet<>();
     for(int i=0;i<rows.size();i++){
       if(!(rows.get(i) instanceof Map<?,?> candidate)||!(bindings.get(i) instanceof Map<?,?> binding)
               ||decimalInteger(binding.get("input_order"))!=i+1||!(binding.get("file_asset_id") instanceof String asset)||asset.isBlank()
               ||!(binding.get("sha256") instanceof String sha)||sha.isBlank()||!assets.add(asset))return null;
       RecruitmentMatchQualification.Decision d=RecruitmentMatchQualification.evaluate(candidate,rows.size(),i+1);
       if(d==null||!refs.add(String.valueOf(candidate.get("attachment_ref"))))return null;
       if("FAILED".equals(d.itemStatus()))failed++;if("HARD_FILTERED".equals(d.itemStatus()))hard++;
       units=Math.addExact(units,d.units());
     }
     if(decimalInteger(batch.get("failed_count"))!=failed||decimalInteger(batch.get("hard_filtered_count"))!=hard
             ||decimalInteger(batch.get("succeeded_count"))!=rows.size()-failed)return null;
     return units == 0
             ? new Decision(0,"CONFIRMED_NO_RESULT","RD_MATCH_BATCH_CONFIRMED_NO_RESULT")
             : new Decision(units,"VERIFIED_VALID_RESULT","RD_MATCH_BATCH_COMPLETED");
   }
   if("candidate_screening".equals(capabilityCode)){
     BigDecimal score=decimal(data.get("score"));
     if(score==null||score.signum()<0||score.compareTo(BigDecimal.valueOf(100))>0
             ||!(data.get("level") instanceof String level)||level.isBlank()
             ||!stringList(data.get("matched_points"))||!stringList(data.get("unmatched_points"))
             ||!stringList(data.get("negotiable_points"))||!stringList(data.get("missing_information"))
             ||!stringList(data.get("risks"))||!stringList(data.get("evidence")))return null;
     return new Decision(1,"VERIFIED_VALID_RESULT","SIMPLE_MATCH_COMPLETED");
   }
   if("resume_parsing".equals(capabilityCode)){
     if(!(data.get("parsed") instanceof Map<?,?> resume)||!validResume(resume)
             ||!(data.get("analysis_text") instanceof String)||!validWarnings(data.get("warnings")))return null;
     return new Decision(1,"VERIFIED_VALID_RESULT","RESUME_PARSE_COMPLETED");
   }
   if("jd_generation".equals(capabilityCode)){
     boolean fullText=data.get("jd_text") instanceof String body&&!body.isBlank();
     Object rawJob=data.get("structured_job");
     Map<?,?> job=rawJob instanceof Map<?,?> map?map:data;
     if(!(job.get("title") instanceof String title)||title.isBlank()
             ||!(job.containsKey("responsibilities")&&(job.get("responsibilities") instanceof String||stringList(job.get("responsibilities"))))
             ||!(job.containsKey("qualifications")||job.get("requirements") instanceof String||stringList(job.get("requirements")))
             ||!fullText&&!(job.get("requirements") instanceof String||stringList(job.get("requirements"))))return null;
     return new Decision(1,"VERIFIED_VALID_RESULT","JD_GENERATION_COMPLETED");
   }
   if("jd_in_place_revision".equals(capabilityCode)&&"revise".equals(operationCode)){
     if(!(data.get("action") instanceof String action)
             ||!Set.of("UPDATE_CURRENT_JD","CREATE_NEW_JD").contains(action)
             ||!nonBlank(data.get("title"))||!nonBlank(data.get("company_name"))
             ||!nonBlankTextOrList(data.get("responsibilities"))
             ||!nonBlankTextOrList(data.get("requirements"))
             ||!nonBlankTextOrList(data.get("skills")))return null;
     return new Decision(1,"VERIFIED_VALID_RESULT","JD_IN_PLACE_REVISION_COMPLETED");
   }
   if("interview_kit_generation".equals(capabilityCode)){
     if(!(data.get("match_summary") instanceof String summary)||summary.isBlank()
             ||!(data.get("core_competencies") instanceof List<?> competencies)||competencies.size()!=3
             ||!(data.get("questions") instanceof List<?> questions)||questions.size()<4||questions.size()>20)return null;
     for(Object item:competencies)if(!(item instanceof Map<?,?> competency)
             ||!nonBlank(competency.get("name"))||!nonBlank(competency.get("description")))return null;
     String[] required={"category","content","rationale","focus_points","reference_answer_points","scoring_points","evidence_refs","core_competency"};
     for(Object item:questions){if(!(item instanceof Map<?,?> question))return null;for(String field:required)if(!nonBlank(question.get(field)))return null;}
     return new Decision(1,"VERIFIED_VALID_RESULT","INTERVIEW_KIT_GENERATION_COMPLETED");
   }
   if("conversation_route".equals(capabilityCode)&&"route".equals(operationCode)){
     if(!(data.get("kind") instanceof String kind)||!Set.of("ROUTE","CLARIFY","INFORM","UNSUPPORTED","FALLBACK").contains(kind.toUpperCase(Locale.ROOT))
             ||!(data.get("confidence") instanceof Number confidence)||!Double.isFinite(confidence.doubleValue())
             ||confidence.doubleValue()<0||confidence.doubleValue()>1||!stringList(data.get("missing_inputs")))return null;
     return new Decision(1,"VERIFIED_VALID_RESULT","ROUTE_DECISION_COMPLETED");
   }
   return null;
 }

 private static boolean validResume(Map<?,?> resume){
   if(resume.get("basic_info") instanceof Map<?,?> basic){
     return textOrNull(basic.get("name"))&&textOrNull(basic.get("email"))&&textOrNull(basic.get("phone"))
             &&textOrNull(basic.get("location"))&&textOrNull(basic.get("headline"))
             &&resume.get("education") instanceof List<?>&&resume.get("work_experience") instanceof List<?>
             &&resume.get("project_experience") instanceof List<?>&&resume.get("skills") instanceof List<?>;
   }
   return textOrNull(resume.get("name"))&&textOrNull(resume.get("email"))&&textOrNull(resume.get("phone"))
           &&textOrNull(resume.get("location"))&&textOrNull(resume.get("headline"))
           &&(resume.get("years_experience")==null||decimal(resume.get("years_experience"))!=null)
           &&resume.get("skills") instanceof List<?>&&resume.get("skill_details") instanceof List<?>
           &&resume.get("work_experience") instanceof List<?>&&resume.get("education_experience") instanceof List<?>;
 }
 private static boolean textOrNull(Object value){return value==null||value instanceof String;}
 private static boolean nonBlank(Object value){return value instanceof String text&&!text.isBlank();}
 private static boolean nonBlankTextOrList(Object value){
   if(nonBlank(value))return true;
   if(!(value instanceof List<?> items)||items.isEmpty())return false;
   return items.stream().allMatch(ExecutionBillingQualification::nonBlank);
 }
 private static boolean stringList(Object value){return value instanceof List<?> values&&values.stream().allMatch(String.class::isInstance);}
 private static boolean validWarnings(Object value){return value instanceof List<?> values&&values.stream().allMatch(item->item instanceof String||item instanceof Map<?,?> map&&map.get("description") instanceof String&&map.get("verification_question") instanceof String);}
 private static BigDecimal decimal(Object value){try{return value instanceof Number n?new BigDecimal(n.toString()):null;}catch(RuntimeException ex){return null;}}
 private static int decimalInteger(Object value){try{return value instanceof Number n?new BigDecimal(n.toString()).intValueExact():-1;}catch(RuntimeException ex){return -1;}}
}
