<template>
  <div class="fixed inset-0 overflow-hidden bg-background -z-10">
    <div class="absolute inset-0 opacity-40">
      <div class="absolute top-[-10%] left-[-10%] w-[50%] h-[50%] rounded-full bg-primary/30 blur-[100px] animate-blob"></div>
      <div class="absolute top-[20%] right-[-10%] w-[40%] h-[60%] rounded-full bg-tertiary/20 blur-[120px] animate-blob animation-delay-2000"></div>
      <div class="absolute bottom-[-20%] left-[20%] w-[60%] h-[50%] rounded-full bg-secondary/30 blur-[100px] animate-blob animation-delay-4000"></div>
    </div>
  </div>
</template>

<style scoped>
@keyframes blob {
  0% { transform: translate(0px, 0px) scale(1); }
  33% { transform: translate(30px, -50px) scale(1.1); }
  66% { transform: translate(-20px, 20px) scale(0.9); }
  100% { transform: translate(0px, 0px) scale(1); }
}
.animate-blob {
  animation: blob 10s infinite alternate;
}
.animation-delay-2000 {
  animation-delay: 2s;
}
.animation-delay-4000 {
  animation-delay: 4s;
}
</style>
