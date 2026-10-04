<script setup lang="ts">
import { ref, watch } from 'vue'
import { getWorkflowVersion, runWorkflow, type WorkflowInputField, type WorkflowRun, type WorkflowVersionDetail } from '@/api/workflows'

const props=defineProps<{modelValue:boolean;versionId?:string}>()
const emit=defineEmits<{(e:'update:modelValue',value:boolean):void}>()
const loading=ref(false), submitting=ref(false), error=ref(''), input=ref('我想申请退款')
const version=ref<WorkflowVersionDetail>(), fields=ref<WorkflowInputField[]>([]), values=ref<Record<string,string|number|boolean|undefined>>({})
const result=ref<WorkflowRun>()
let generation=0
watch(()=>[props.modelValue,props.versionId] as const,async ([open,id])=>{
  const token=++generation
  version.value=undefined; fields.value=[]; values.value={}; result.value=undefined; error.value=''; submitting.value=false; loading.value=false
  if(!open||!id)return
  loading.value=true
  try{
    const published=await getWorkflowVersion(id)
    if(token!==generation)return
    const schema=published.nodes.find(n=>n.type.toUpperCase()==='START')?.config.inputs
    if(schema!==undefined&&!Array.isArray(schema))throw new Error('发布版本的输入schema无效，请重新发布')
    fields.value=(schema??[]) as WorkflowInputField[]
    fields.value.forEach(field=>{values.value[field.name]=field.default??(field.type==='boolean'?false:field.type==='text'?'':undefined)})
    version.value=published
  }catch(failure){if(token===generation)error.value=failure instanceof Error?failure.message:'读取发布输入失败'}
  finally{if(token===generation)loading.value=false}
},{immediate:true})

async function submit(){
  if(!version.value||submitting.value)return
  error.value=''; result.value=undefined
  if(!input.value.trim()||input.value.length>20000){error.value='userMessage须为1至20000字符';return}
  const inputs:Record<string,string|number|boolean>={}
  for(const field of fields.value){
    const value=values.value[field.name]
    if(value===undefined){if(field.required){error.value=`请填写 ${field.label??field.name}`;return}continue}
    if(field.type==='text'&&(typeof value!=='string'||value.length>(field.maxLength??2000)||(field.required&&!value.trim()))){error.value=`请检查 ${field.label??field.name} 的文本长度`;return}
    if(field.type==='number'&&(typeof value!=='number'||!Number.isFinite(value)||Math.abs(value)>1e12)){error.value=`请填写有效数字 ${field.label??field.name}`;return}
    if(field.type==='enum'&&(typeof value!=='string'||!field.options?.includes(value))){error.value=`请选择 ${field.label??field.name}`;return}
    inputs[field.name]=value
  }
  const token=generation, id=version.value.id
  submitting.value=true
  try{const executed=await runWorkflow(id,input.value,inputs);if(token===generation)result.value=executed}
  catch(failure){if(token===generation)error.value=failure instanceof Error?failure.message:'试运行失败'}
  finally{if(token===generation)submitting.value=false}
}
</script>

<template>
  <el-dialog :model-value="modelValue" title="运行已发布版本" width="650px" append-to-body :close-on-click-modal="false" @update:model-value="emit('update:modelValue',$event)">
    <div v-loading="loading">
      <p v-if="version">v{{version.versionNo}} · {{version.id}}<br><small>按发布快照填写；当前草稿修改不会改变本次运行。关闭窗口不会取消服务端执行。</small></p>
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/>
      <el-form v-if="version" label-position="top" :disabled="submitting">
        <el-form-item label="userMessage" required><el-input v-model="input" aria-label="userMessage" type="textarea" :maxlength="20000"/></el-form-item>
        <el-form-item v-for="field in fields" :key="field.name" :label="field.label??field.name" :required="field.required">
          <el-input v-if="field.type==='text'" :model-value="String(values[field.name]??'')" :aria-label="field.label??field.name" :maxlength="field.maxLength??2000" @update:model-value="values[field.name]=$event"/>
          <el-input-number v-else-if="field.type==='number'" :model-value="values[field.name] as number|undefined" :aria-label="field.label??field.name" :min="-1e12" :max="1e12" @update:model-value="values[field.name]=$event"/>
          <el-switch v-else-if="field.type==='boolean'" :model-value="values[field.name]===true" :aria-label="field.label??field.name" @update:model-value="values[field.name]=$event===true"/>
          <el-select v-else-if="field.type==='enum'" :model-value="values[field.name] as string|undefined" :aria-label="field.label??field.name" @update:model-value="values[field.name]=$event"><el-option v-for="option in field.options" :key="option" :label="option" :value="option"/></el-select>
        </el-form-item>
      </el-form>
      <section v-if="result" role="status"><b>{{result.status}}</b><pre>{{result.output??result.errorMessage}}</pre><small>{{result.nodes.length}} nodes · {{result.elapsedMs??0}} ms · Run {{result.id}}</small></section>
    </div>
    <template #footer><el-button @click="emit('update:modelValue',false)">关闭</el-button><el-button type="primary" :disabled="!version||loading" :loading="submitting" @click="submit">执行已发布版本</el-button></template>
  </el-dialog>
</template>
<style scoped>small{color:var(--color-text-secondary)}pre{white-space:pre-wrap;overflow-wrap:anywhere;max-height:30vh;overflow:auto}.el-alert{margin-bottom:14px}</style>
