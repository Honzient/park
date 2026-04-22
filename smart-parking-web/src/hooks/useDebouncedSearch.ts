import { onBeforeUnmount } from 'vue';

export const useDebouncedSearch = (callback: () => void, delay = 500): (() => void) => {
  let timer: number | undefined;

  const trigger = (): void => {
    if (timer) {
      window.clearTimeout(timer);
    }
    timer = window.setTimeout(() => {
      callback();
    }, delay);
  };

  onBeforeUnmount(() => {
    if (timer) {
      window.clearTimeout(timer);
    }
  });

  return trigger;
};
