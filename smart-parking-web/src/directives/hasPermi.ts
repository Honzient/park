import type { Directive } from 'vue';
import { useAuthStore } from '@/store/auth';
import { pinia } from '@/store';

const hasPermission = (value: string | string[]): boolean => {
  const authStore = useAuthStore(pinia);
  const expected = Array.isArray(value) ? value : [value];
  return expected.some((permission) => authStore.hasPermission(permission));
};

const updateVisibility = (el: HTMLElement, value: string | string[] | undefined): void => {
  if (!value || hasPermission(value)) {
    el.style.display = '';
    el.removeAttribute('aria-hidden');
    return;
  }
  el.style.display = 'none';
  el.setAttribute('aria-hidden', 'true');
};

export const hasPermiDirective: Directive<HTMLElement, string | string[]> = {
  mounted(el, binding) {
    updateVisibility(el, binding.value);
  },
  updated(el, binding) {
    updateVisibility(el, binding.value);
  },
};
