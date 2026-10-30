import { Flex, Text } from "@radix-ui/themes";
import { formatPayload, type ListedEvent } from "../api/events";

export function EventList(props: { events: readonly ListedEvent[] }) {
  return (
    <Flex direction="column" gap="1">
      {props.events.length === 0 ? <Text>暂无事件</Text> : props.events.map((event) => (
        <Text key={event.sequence} size="2">
          {event.sequence} {event.type} {event.occurredAt} {formatPayload(event.payload)}
        </Text>
      ))}
    </Flex>
  );
}
