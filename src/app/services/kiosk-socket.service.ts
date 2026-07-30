import { Injectable } from '@angular/core';
import { Subject } from 'rxjs';
import type { WSMessage, Command } from '../models/types';

/**
 * Thin wrapper around the browser-standard WebSocket with exponential-backoff
 * reconnect. TV boxes sit on flaky wifi for weeks at a stretch, so reconnect
 * logic matters more here than in a typical web app.
 */
@Injectable({ providedIn: 'root' })
export class KioskSocketService {
  private ws: WebSocket | null = null;
  private closedByUser = false;
  private retryDelayMs = 1000;
  private readonly maxRetryDelayMs = 30_000;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;
  private url = '';

  readonly command$ = new Subject<Command>();
  readonly open$ = new Subject<void>();
  readonly close$ = new Subject<void>();

  connect(url: string): void {
    this.url = url;
    this.closedByUser = false;
    this.open();
  }

  private open(): void {
    const ws = new WebSocket(this.url);
    this.ws = ws;

    ws.onopen = () => {
      this.retryDelayMs = 1000;
      this.open$.next();
    };

    ws.onmessage = (event) => {
      let msg: WSMessage;
      try {
        msg = JSON.parse(event.data);
      } catch {
        return;
      }
      if (msg.type === 'command') {
        this.command$.next(msg.command);
      }
    };

    ws.onclose = () => {
      this.close$.next();
      if (!this.closedByUser) this.scheduleReconnect();
    };

    ws.onerror = () => {
      // onclose fires right after onerror for WebSocket; reconnect is
      // handled there so we don't double-schedule here.
    };
  }

  private scheduleReconnect(): void {
    if (this.retryTimer) return;
    this.retryTimer = setTimeout(() => {
      this.retryTimer = null;
      this.open();
    }, this.retryDelayMs);
    this.retryDelayMs = Math.min(this.retryDelayMs * 2, this.maxRetryDelayMs);
  }

  sendAck(commandId: string, status: 'acked' | 'failed', message?: string): void {
    const msg: WSMessage = { type: 'command_ack', commandId, status, message };
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(msg));
    }
  }

  close(): void {
    this.closedByUser = true;
    if (this.retryTimer) clearTimeout(this.retryTimer);
    this.ws?.close();
  }
}
