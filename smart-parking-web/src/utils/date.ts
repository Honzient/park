const pad = (value: number): string => String(value).padStart(2, '0');

export const formatDate = (date: Date): string => {
  const year = date.getFullYear();
  const month = pad(date.getMonth() + 1);
  const day = pad(date.getDate());
  return `${year}-${month}-${day}`;
};

export const formatDateTime = (date: Date): string => {
  const hours = pad(date.getHours());
  const minutes = pad(date.getMinutes());
  const seconds = pad(date.getSeconds());
  return `${formatDate(date)} ${hours}:${minutes}:${seconds}`;
};

export const getDateRangeLastDays = (days: number): [string, string] => {
  const end = new Date();
  const start = new Date();
  start.setDate(end.getDate() - days + 1);
  return [formatDate(start), formatDate(end)];
};

export const withDayBoundary = (range: [string, string]): [string, string] => {
  return [`${range[0]} 00:00:00`, `${range[1]} 23:59:59`];
};

export const isRangeReversed = (range: [string, string]): boolean => {
  if (!range[0] || !range[1]) {
    return false;
  }
  return new Date(range[0]).getTime() > new Date(range[1]).getTime();
};

export const getTodayRange = (): [string, string] => {
  const today = formatDate(new Date());
  return [today, today];
};

export const getWeekRange = (): [string, string] => {
  const now = new Date();
  const day = now.getDay() || 7;
  const monday = new Date(now);
  monday.setDate(now.getDate() - day + 1);
  return [formatDate(monday), formatDate(now)];
};

export const getMonthRange = (): [string, string] => {
  const now = new Date();
  const firstDay = new Date(now.getFullYear(), now.getMonth(), 1);
  return [formatDate(firstDay), formatDate(now)];
};
