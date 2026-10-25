package cn.servicehub.support;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;

/** Validates frozen structural types plus explicitly exported additive Java fields. */
public final class ContractSchema {
 private final JsonNode document;
 public ContractSchema(JsonNode document){this.document=document;}
 public void response(JsonNode operation,int status,JsonNode body){
  JsonNode response=operation.path("responses").path(String.valueOf(status));
  var schema=response.path("content").path("application/json").path("schema");
  if(!schema.isMissingNode())validate(schema,body,"$");
 }
 public void validate(JsonNode schema,JsonNode value,String path){
  if(schema.has("$ref")){validate(resolve(schema.get("$ref").asString()),value,path);return;}
  if(schema.has("anyOf")){
   boolean accepted=false;
   for(var branch:schema.get("anyOf"))try{validate(branch,value,path);accepted=true;break;}catch(AssertionError ignored){}
   assertThat(accepted).as(path+" must match one declared type").isTrue();return;
  }
  if(schema.has("allOf"))for(var branch:schema.get("allOf"))validate(branch,value,path);
  if(schema.has("enum")){
   boolean accepted=false;for(var allowed:schema.get("enum"))if(allowed.equals(value))accepted=true;
   assertThat(accepted).as(path+" enum").isTrue();
  }
  if(schema.has("type")&&schema.get("type").isArray()){
   boolean accepted=false;
   for(var type:schema.get("type"))if(matches(type.asString(),value))accepted=true;
   assertThat(accepted).as(path+" type").isTrue();
  }else if(schema.has("type"))assertThat(matches(schema.get("type").asString(),value)).as(path+" type="+schema.get("type").asString()).isTrue();
  if(value==null||value.isNull())return;
  if(value.isObject()){
   for(var required:schema.path("required"))assertThat(value.has(required.asString())).as(path+" required "+required.asString()).isTrue();
   var properties=schema.path("properties");
   for(String name:value.propertyNames()){
    if(properties.has(name))validate(properties.get(name),value.get(name),path+"."+name);
    else if(schema.path("additionalProperties").isObject())validate(schema.get("additionalProperties"),value.get(name),path+"."+name);
    else if(schema.path("additionalProperties").isBoolean()&&!schema.path("additionalProperties").asBoolean())fail(path+" has undeclared property "+name);
   }
  }
  if(value.isArray()){
   if(schema.has("minItems"))assertThat(value.size()).as(path).isGreaterThanOrEqualTo(schema.get("minItems").asInt());
   if(schema.has("maxItems"))assertThat(value.size()).as(path).isLessThanOrEqualTo(schema.get("maxItems").asInt());
   int i=0;for(var item:value)if(schema.has("items"))validate(schema.get("items"),item,path+"["+(i++)+"]");
  }
  if(value.isString()){
   String text=value.asString();
   if(schema.has("minLength"))assertThat(text.codePointCount(0,text.length())).as(path).isGreaterThanOrEqualTo(schema.get("minLength").asInt());
   if(schema.has("maxLength"))assertThat(text.codePointCount(0,text.length())).as(path).isLessThanOrEqualTo(schema.get("maxLength").asInt());
   if(schema.has("pattern"))assertThat(Pattern.compile(schema.get("pattern").asString()).matcher(text).find()).as(path+" pattern").isTrue();
   if("date-time".equals(schema.path("format").asString())){
    assertThatCode(()->Instant.parse(text)).as(path+" UTC timestamp").doesNotThrowAnyException();
    assertThat(text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{6})?Z")).as(path+" original microsecond wire format").isTrue();
   }
  }
  if(value.isNumber()){
   double number=value.asDouble();
   if(schema.has("minimum"))assertThat(number).as(path).isGreaterThanOrEqualTo(schema.get("minimum").asDouble());
   if(schema.has("maximum"))assertThat(number).as(path).isLessThanOrEqualTo(schema.get("maximum").asDouble());
   if(schema.has("exclusiveMinimum"))assertThat(number).as(path).isGreaterThan(schema.get("exclusiveMinimum").asDouble());
  }
 }
 private boolean matches(String type,JsonNode value){
  if(value==null)return type.equals("null");return switch(type){case "object"->value.isObject();case "array"->value.isArray();case "string"->value.isString();case "number"->value.isNumber();case "integer"->value.isIntegralNumber();case "boolean"->value.isBoolean();case "null"->value.isNull();default->throw new AssertionError("Unsupported schema type "+type);};
 }
 private JsonNode resolve(String ref){
  assertThat(ref).startsWith("#/");JsonNode node=document;for(String part:ref.substring(2).split("/"))node=node.path(part.replace("~1","/").replace("~0","~"));
  assertThat(node.isMissingNode()).as("Unknown contract reference").isFalse();return node;
 }
}
