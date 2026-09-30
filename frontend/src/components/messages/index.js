// 消息类型 → 组件映射（未知类型兜底按 text 渲染，后端新增类型不炸前端）
import TextCard from './TextCard.vue'
import AgentTraceCard from './AgentTraceCard.vue'
import DiagnosisCard from './DiagnosisCard.vue'
import PlanCard from './PlanCard.vue'
import ExerciseCard from './ExerciseCard.vue'
import GradeCard from './GradeCard.vue'
import ReportCard from './ReportCard.vue'
import SystemCard from './SystemCard.vue'
import CourseListCard from './CourseListCard.vue'
import CourseOrderCard from './CourseOrderCard.vue'

export const cardMap = {
  text: TextCard,
  agent_trace: AgentTraceCard,
  diagnosis: DiagnosisCard,
  plan: PlanCard,
  exercise: ExerciseCard,
  grade: GradeCard,
  report: ReportCard,
  system: SystemCard,
  course_list: CourseListCard,
  course_order: CourseOrderCard,
}

export { TextCard }
