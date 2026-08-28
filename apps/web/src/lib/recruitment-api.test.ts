import { beforeEach, describe, expect, it, vi } from "vitest";
import { apiFetch, apiStream } from "@/lib/api-client";
import { deleteTask, renameTask, streamJdRunEvents } from "@/lib/recruitment-api";

vi.mock("@/lib/api-client", () => ({
  apiFetch: vi.fn(),
  apiStream: vi.fn(),
}));

describe("streamJdRunEvents", () => {
  beforeEach(() => vi.clearAllMocks());

  it("sends the replay cursor and parses persisted SSE events", async () => {
    const frames = [
      "id: 42\nevent: delta\ndata: {\"delta\":\"正在生成\",\"progress\":50}\n\n",
      "id: 43\nevent: completed\ndata: {\"status\":\"COMPLETED\",\"progress\":100}\n\n",
    ];
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        for (const frame of frames) controller.enqueue(new TextEncoder().encode(frame));
        controller.close();
      },
    });
    vi.mocked(apiStream).mockResolvedValue(new Response(body));
    const events: Array<{ id: number; type: string }> = [];

    await streamJdRunEvents("workspace", "task", 41, event => events.push(event), new AbortController().signal);

    expect(apiStream).toHaveBeenCalledWith(
      "/workspaces/workspace/recruitment-tasks/task/jd-runs/events",
      expect.objectContaining({ headers: { "Last-Event-ID": "41" } }),
    );
    expect(events).toEqual([
      expect.objectContaining({ id: 42, type: "delta" }),
      expect.objectContaining({ id: 43, type: "completed" }),
    ]);
  });
});

describe("task lifecycle requests", () => {
  beforeEach(() => vi.clearAllMocks());

  it("renames and deletes tasks through the scoped endpoints", async () => {
    vi.mocked(apiFetch).mockResolvedValue({ id: "task-1", title: "新名称" } as never);

    await renameTask("workspace-1", "task-1", "新名称");
    await deleteTask("workspace-1", "task-1");

    expect(apiFetch).toHaveBeenNthCalledWith(1, "/workspaces/workspace-1/recruitment-tasks/task-1", {
      method: "PUT", body: JSON.stringify({ title: "新名称" }),
    });
    expect(apiFetch).toHaveBeenNthCalledWith(2, "/workspaces/workspace-1/recruitment-tasks/task-1", { method: "DELETE" });
  });
});
