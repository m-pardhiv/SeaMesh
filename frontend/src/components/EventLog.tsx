import type { SeaEvent } from '../types';
import { MAX_EVENTS } from '../config';
import { formatClock } from '../lib/format';

const TYPE_LABEL: Record<SeaEvent['type'], string> = { sos: 'SOS', position: 'POS', resolved: 'OK' };

export function EventLog({ events }: { events: SeaEvent[] }) {
  return (
    <section className="card events-card" id="events-card" aria-labelledby="events-title">
      <div className="panel-head">
        <h2 id="events-title">Event log</h2>
        <span className="muted">Newest first · last {MAX_EVENTS} kept</span>
      </div>
      {events.length === 0 ? (
        <p className="empty">No events yet.</p>
      ) : (
        <ol className="events">
          {events.map((e) => (
            <li key={e.id} className={`event event--${e.type}`}>
              <time className="mono" dateTime={new Date(e.time).toISOString()}>{formatClock(e.time)}</time>
              <span className={`event-type event-type--${e.type}`}>{TYPE_LABEL[e.type]}</span>
              <span className="event-text">{e.text}</span>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
