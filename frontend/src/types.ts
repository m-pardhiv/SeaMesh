// Shapes sent by the SeaMesh server (see README for the full contract).

export type BoatStatus = 'normal' | 'sos';
export type SosTrigger = 'BTN' | 'WATER' | '-';

export interface Boat {
  id: string;
  lat: number;
  lon: number;
  status: BoatStatus;
  trigger: SosTrigger;
  /** ms epoch when the server last got a packet from this boat */
  lastSeen: number;
  /** ms epoch when the current SOS started */
  sosSince: number | null;
  /** the SOS acknowledgement was delivered back to the boat */
  acked: boolean;
  /** relay hops; 0 = heard directly by the gateway boat */
  hops: number;
  /** last boat that transmitted the packet */
  via: string;
  /** boat whose phone uploaded the packet to the internet */
  gateway: string;
  /** signal strength of the last hop, dBm */
  rssi: number;
  msgId: number;
  /** last positions, oldest first */
  trail: [number, number][];
}

export type SeaEventType = 'sos' | 'position' | 'resolved';

export interface SeaEvent {
  id: string;
  time: number;
  type: SeaEventType;
  boat: string;
  text: string;
}

export type ConnState = 'connecting' | 'live' | 'reconnecting' | 'offline';
