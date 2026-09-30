<template>
  <component :is="resolved" :msg="msg" />
</template>

<script setup>
import { computed } from 'vue'
import { cardMap, TextCard } from './index'

const props = defineProps({ msg: { type: Object, required: true } })
const resolved = computed(() => cardMap[props.msg.type] || TextCard)
</script>
