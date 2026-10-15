import type { Row } from "./workspace";
import { time } from "./ui";
export function monthAnchor(offset: number) {
  const now = new Date();
  return new Date(now.getFullYear(), now.getMonth() + offset, 1);
}
export function MonthCalendar({
  offset,
  appointments,
  onSelect,
  patientName,
}: {
  offset: number;
  appointments: Row[];
  onSelect: (r: Row) => void;
  patientName: (id?: string) => string;
}) {
  const first = monthAnchor(offset),
    start = new Date(first);
  start.setDate(start.getDate() - ((start.getDay() + 6) % 7));
  return (
    <div className="table-scroll">
      <div className="month-grid">
        {["Seg", "Ter", "Qua", "Qui", "Sex", "Sáb", "Dom"].map((day) => (
          <strong className="month-heading" key={day}>
            {day}
          </strong>
        ))}
        {Array.from({ length: 42 }, (_, i) => {
          const day = new Date(start);
          day.setDate(start.getDate() + i);
          const list = appointments.filter(
            (a) => new Date(a.startAt!).toDateString() === day.toDateString(),
          );
          return (
            <div
              className={
                "month-cell " +
                (day.getMonth() === first.getMonth() ? "" : "month-outside")
              }
              key={i}
            >
              <span className="count-label">{day.getDate()}</span>
              {list.map((a) => (
                <button
                  className={
                    "month-appointment " +
                    (a.status === "CONFIRMED" ? "confirmed" : "")
                  }
                  key={a.id}
                  onClick={() => onSelect(a)}
                >
                  <strong>{time(a.startAt!)}</strong>
                  <span>{patientName(a.patientId)}</span>
                </button>
              ))}
            </div>
          );
        })}
      </div>
    </div>
  );
}
