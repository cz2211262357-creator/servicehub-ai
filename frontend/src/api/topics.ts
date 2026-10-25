import { api, post } from "./client";
import type { Schemas } from "./client";
export type TopicSwitchInput = {
  mode: "NEW" | "RESUME";
  topic_id?: number;
  expected_generation_epoch: number;
  pending_message_id?: number;
};
export function interruptConversation(id: number, expectedEpoch: number) {
  return post<Schemas["ConversationDTO"]>(
    "/conversations/" + id + "/interrupt",
    { expected_generation_epoch: expectedEpoch },
  );
}
export function switchConversationTopic(id: number, input: TopicSwitchInput) {
  return post<Schemas["ConversationDTO"]>(
    "/conversations/" + id + "/topics/switch",
    input,
  );
}
export function listConversationTopics(id: number) {
  return api<Schemas["TopicPage"]>("/conversations/" + id + "/topics");
}
