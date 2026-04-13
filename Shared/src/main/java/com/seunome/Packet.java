package com.seunome;

public class Packet {

    public enum Type {
        REGISTER,
        MESSAGE,
        ACK_DELIVERED,
        ACK_READ,
        HISTORY_REQUEST,
        HISTORY_RESPONSE
    }

    private Type   type;
    private String from;
    private String to;
    private String content;
    private String timestamp;
    private String messageId;
    private String status;
    private String name;
    private String nickname;

    public Packet() {}

    public Type   getType()      { return type; }
    public String getFrom()      { return from; }
    public String getTo()        { return to; }
    public String getContent()   { return content; }
    public String getTimestamp() { return timestamp; }
    public String getMessageId() { return messageId; }
    public String getStatus()    { return status; }
    public String getName()      { return name; }
    public String getNickname()  { return nickname; }

    public void setType(Type type)           { this.type = type; }
    public void setFrom(String from)         { this.from = from; }
    public void setTo(String to)             { this.to = to; }
    public void setContent(String content)   { this.content = content; }
    public void setTimestamp(String ts)      { this.timestamp = ts; }
    public void setMessageId(String id)      { this.messageId = id; }
    public void setStatus(String status)     { this.status = status; }
    public void setName(String name)         { this.name = name; }
    public void setNickname(String nickname) { this.nickname = nickname; }
}