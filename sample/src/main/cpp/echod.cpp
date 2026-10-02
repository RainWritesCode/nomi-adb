#include <arpa/inet.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <signal.h>
#include <sys/socket.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <string_view>
#include <thread>

namespace {

constexpr size_t kMaxLine = 128;
constexpr int kMaxClients = 8;

std::string gToken;
int gPort = 0;
std::atomic<int> gClients{0};

bool sendAll(int fd, const char* data, size_t length) {
    while (length > 0) {
        ssize_t sent = send(fd, data, length, MSG_NOSIGNAL);
        if (sent < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        data += sent;
        length -= size_t(sent);
    }
    return true;
}

bool sendText(int fd, std::string_view text) { return sendAll(fd, text.data(), text.size()); }

bool readLine(int fd, std::string& line) {
    line.clear();
    while (line.size() < kMaxLine) {
        char c = 0;
        ssize_t got = recv(fd, &c, 1, 0);
        if (got < 0 && errno == EINTR) continue;
        if (got <= 0) return false;
        if (c == '\n') return true;
        line.push_back(c);
    }
    return false;
}

bool authenticate(int client) {
    timeval timeout{5, 0};
    setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    std::string given;
    if (!readLine(client, given)) return false;
    if (given.size() != gToken.size()) return false;
    unsigned char difference = 0;
    for (size_t i = 0; i < given.size(); i++) difference |= uint8_t(given[i] ^ gToken[i]);
    timeval none{0, 0};
    setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &none, sizeof(none));
    return difference == 0;
}

void serve(int client) {
    if (!authenticate(client)) return;
    char hello[64];
    std::snprintf(hello, sizeof(hello), "hello uid=%d pid=%d\n", int(getuid()), int(getpid()));
    if (!sendText(client, hello)) return;
    char buffer[4096];
    while (true) {
        ssize_t got = recv(client, buffer, sizeof(buffer), 0);
        if (got < 0 && errno == EINTR) continue;
        if (got <= 0) return;
        if (!sendAll(client, buffer, size_t(got))) return;
    }
}

std::string readToken(const char* path) {
    FILE* file = std::fopen(path, "r");
    if (!file) return {};
    char buffer[128] = {};
    size_t got = std::fread(buffer, 1, sizeof(buffer) - 1, file);
    std::fclose(file);
    std::string_view text(buffer, got);
    while (!text.empty() && (text.back() == '\n' || text.back() == '\r' || text.back() == ' ')) text.remove_suffix(1);
    return std::string(text);
}

}

int main(int argc, char** argv) {
    for (int i = 1; i + 1 < argc; i += 2) {
        std::string_view key = argv[i];
        if (key == "--port") gPort = std::atoi(argv[i + 1]);
        else if (key == "--token-file") gToken = readToken(argv[i + 1]);
    }
    if (gPort <= 0 || gPort > 65535 || gToken.size() < 32) return 2;
    signal(SIGPIPE, SIG_IGN);
    signal(SIGHUP, SIG_IGN);
    int server = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (server < 0) return 3;
    int yes = 1;
    setsockopt(server, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));
    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_port = htons(uint16_t(gPort));
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (bind(server, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0) return 3;
    if (listen(server, 4) != 0) return 4;
    while (true) {
        int client = accept4(server, nullptr, nullptr, SOCK_CLOEXEC);
        if (client < 0) {
            if (errno == EINTR) continue;
            usleep(200000);
            continue;
        }
        if (gClients.load() >= kMaxClients) {
            close(client);
            continue;
        }
        setsockopt(client, IPPROTO_TCP, TCP_NODELAY, &yes, sizeof(yes));
        gClients++;
        std::thread([client] {
            serve(client);
            close(client);
            gClients--;
        }).detach();
    }
}
