import { Button, Flex, Text } from "@radix-ui/themes";
import type { KeyboardEvent } from "react";
import type { ControlAvailability } from "./logic/controls";
import type { ControlKey, RoomSnapshot, SseState } from "./types";

const CONTROLS: readonly { type: ControlKey; label: string }[] = [
  { type: "start", label: "开始" },
  { type: "pause", label: "暂停" },
  { type: "step", label: "单步" },
  { type: "continue", label: "继续" },
  { type: "reset", label: "重置" },
];

export function ControlBar(props: {
  room: RoomSnapshot | null;
  sse: SseState;
  commandError: string | null;
  commandUncertain: boolean;
  gaveUp: boolean;
  availability: ControlAvailability;
  acknowledge: ControlAvailability;
  onCommand: (type: ControlKey) => void;
  onReconnect: () => void;
}) {
  const { room } = props;
  const finished = room?.matchStatus === "FINISHED" || room?.matchStatus === "ABORTED";
  return (
    <Flex direction="column" gap="2">
      {room ? (
        <>
          <Text>执行模式 {room.executionMode}</Text>
          <Text>执行状态 {room.executionStatus}</Text>
          <Text>对局状态 {room.matchStatus}</Text>
          {finished && room.result ? (
            <Text>终局 {room.result.status} {room.result.reason ?? ""}</Text>
          ) : null}
          {room.health !== "OK" ? <Text color="red">房间健康 {room.health} {room.healthError ?? ""}</Text> : null}
        </>
      ) : (
        <Text>正在读取房间</Text>
      )}
      <Text>事件流：{sseLabel(props.sse)}</Text>
      {props.commandUncertain ? (
        <Text color="red">命令结果不确定。再次点击开始、单步或继续会发出新命令。</Text>
      ) : null}
      {props.commandError ? <Text color="red">{props.commandError}</Text> : null}
      <Flex gap="2" wrap="wrap">
        {CONTROLS.map((control) => (
          <ControlButton
            key={control.type}
            label={control.label}
            enabled={props.availability[control.type]}
            acknowledge={props.acknowledge[control.type]}
            onCommand={() => props.onCommand(control.type)}
          />
        ))}
        {props.gaveUp ? <Button type="button" onClick={props.onReconnect}>重新连接</Button> : null}
      </Flex>
    </Flex>
  );
}

function ControlButton(props: {
  label: string;
  enabled: boolean;
  acknowledge: boolean;
  onCommand: () => void;
}) {
  if (!props.acknowledge) {
    return (
      <Button type="button" disabled={!props.enabled} onClick={props.onCommand}>
        {props.label}
      </Button>
    );
  }
  const onKeyDown = (event: KeyboardEvent<HTMLSpanElement>) => {
    if (event.key !== "Enter" && event.key !== " ") return;
    event.preventDefault();
    props.onCommand();
  };
  return (
    <span role="button" tabIndex={0} aria-label={props.label} onClick={props.onCommand} onKeyDown={onKeyDown}>
      <Button type="button" disabled tabIndex={-1} aria-hidden style={{ pointerEvents: "none" }}>
        {props.label}
      </Button>
    </span>
  );
}

function sseLabel(sse: SseState): string {
  if (sse === "open") return "已连接";
  if (sse === "connecting") return "连接中";
  return "已断开";
}
