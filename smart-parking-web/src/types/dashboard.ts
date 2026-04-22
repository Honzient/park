export interface DashboardCards {
  totalSpots: number;
  occupiedSpots: number;
  freeSpots: number;
  todayIncome: number;
  incomeTrendPercent: number;
}

export interface DashboardSpot {
  spotNo: string;
  status: 'FREE' | 'OCCUPIED' | 'MAINTENANCE' | 'RESERVED';
  plateNumber: string | null;
  entryTime: string | null;
}

export interface DashboardRecentRecord {
  plateNumber: string;
  entryTime: string;
  exitTime: string | null;
  status: string;
}

export interface DashboardTrendPoint {
  label: string;
  traffic: number;
  income: number;
}

export interface DashboardRealtime {
  cards: DashboardCards;
  spots: DashboardSpot[];
  recentRecords: DashboardRecentRecord[];
  trend: DashboardTrendPoint[];
}
