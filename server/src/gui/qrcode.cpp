#include "gui/qrcode.h"

#include <algorithm>
#include <stdexcept>

namespace
{
    class BitBuffer
    {
    public:
        void AppendBits(int value, int length)
        {
            for (int i = length - 1; i >= 0; --i)
            {
                bits.push_back(((value >> i) & 1) != 0);
            }
        }

        int Size() const
        {
            return static_cast<int>(bits.size());
        }

        bool Get(int index) const
        {
            return bits[index];
        }

    private:
        std::vector<bool> bits;
    };
}

QrCode::QrCode()
    : modules(SIZE, std::vector<bool>(SIZE, false)),
      functionModules(SIZE, std::vector<bool>(SIZE, false))
{
}

QrCode QrCode::EncodeText(const std::string& text)
{
    QrCode qr;
    qr.DrawFunctionPatterns();
    qr.DrawCodewords(AddEcc(MakeDataCodewords(text)));
    qr.DrawFormatBits();
    return qr;
}

int QrCode::Size() const
{
    return SIZE;
}

bool QrCode::GetModule(int x, int y) const
{
    return 0 <= x && x < SIZE && 0 <= y && y < SIZE && modules[y][x];
}

void QrCode::DrawFunctionPatterns()
{
    DrawFinderPattern(3, 3);
    DrawFinderPattern(SIZE - 4, 3);
    DrawFinderPattern(3, SIZE - 4);

    for (int i = 0; i < SIZE; ++i)
    {
        SetFunctionModule(6, i, i % 2 == 0);
        SetFunctionModule(i, 6, i % 2 == 0);
    }

    DrawAlignmentPattern(30, 30);

    for (int i = 0; i <= 8; ++i)
    {
        if (i != 6)
        {
            SetFunctionModule(8, i, false);
            SetFunctionModule(i, 8, false);
        }
    }

    for (int i = 0; i < 8; ++i)
    {
        SetFunctionModule(SIZE - 1 - i, 8, false);
        SetFunctionModule(8, SIZE - 1 - i, false);
    }

    SetFunctionModule(8, SIZE - 8, true);
}

void QrCode::DrawFinderPattern(int x, int y)
{
    for (int dy = -4; dy <= 4; ++dy)
    {
        for (int dx = -4; dx <= 4; ++dx)
        {
            const int xx = x + dx;
            const int yy = y + dy;
            if (xx < 0 || xx >= SIZE || yy < 0 || yy >= SIZE)
            {
                continue;
            }

            const int dist = std::max(std::abs(dx), std::abs(dy));
            SetFunctionModule(xx, yy, dist != 2 && dist != 4);
        }
    }
}

void QrCode::DrawAlignmentPattern(int x, int y)
{
    for (int dy = -2; dy <= 2; ++dy)
    {
        for (int dx = -2; dx <= 2; ++dx)
        {
            SetFunctionModule(x + dx, y + dy, std::max(std::abs(dx), std::abs(dy)) != 1);
        }
    }
}

void QrCode::DrawFormatBits()
{
    const int bits = GetFormatBits();

    for (int i = 0; i <= 5; ++i) SetModule(8, i, ((bits >> i) & 1) != 0);
    SetModule(8, 7, ((bits >> 6) & 1) != 0);
    SetModule(8, 8, ((bits >> 7) & 1) != 0);
    SetModule(7, 8, ((bits >> 8) & 1) != 0);
    for (int i = 9; i < 15; ++i) SetModule(14 - i, 8, ((bits >> i) & 1) != 0);

    for (int i = 0; i < 8; ++i) SetModule(SIZE - 1 - i, 8, ((bits >> i) & 1) != 0);
    for (int i = 8; i < 15; ++i) SetModule(8, SIZE - 15 + i, ((bits >> i) & 1) != 0);
    SetModule(8, SIZE - 8, true);
}

void QrCode::DrawCodewords(const std::vector<unsigned char>& codewords)
{
    int bitIndex = 0;
    int direction = -1;
    int x = SIZE - 1;
    int y = SIZE - 1;
    const int bitCount = static_cast<int>(codewords.size() * 8);

    while (x > 0)
    {
        if (x == 6)
        {
            --x;
        }

        for (;;)
        {
            for (int i = 0; i < 2; ++i)
            {
                const int xx = x - i;
                if (!functionModules[y][xx])
                {
                    bool isBlack = false;
                    if (bitIndex < bitCount)
                    {
                        isBlack = ((codewords[bitIndex >> 3] >> (7 - (bitIndex & 7))) & 1) != 0;
                        ++bitIndex;
                    }

                    if ((xx + y) % 2 == 0)
                    {
                        isBlack = !isBlack;
                    }
                    SetModule(xx, y, isBlack);
                }
            }

            y += direction;
            if (y < 0 || y >= SIZE)
            {
                y -= direction;
                direction = -direction;
                break;
            }
        }

        x -= 2;
    }
}

void QrCode::SetFunctionModule(int x, int y, bool isBlack)
{
    SetModule(x, y, isBlack);
    functionModules[y][x] = true;
}

void QrCode::SetModule(int x, int y, bool isBlack)
{
    modules[y][x] = isBlack;
}

std::vector<unsigned char> QrCode::MakeDataCodewords(const std::string& text)
{
    if (text.size() > 106)
    {
        throw std::runtime_error("Pairing payload is too long for QR version 5-L.");
    }

    BitBuffer buffer;
    buffer.AppendBits(0x4, 4);
    buffer.AppendBits(static_cast<int>(text.size()), 8);
    for (unsigned char c : text)
    {
        buffer.AppendBits(c, 8);
    }

    const int capacityBits = DATA_CODEWORDS * 8;
    buffer.AppendBits(0, std::min(4, capacityBits - buffer.Size()));
    while (buffer.Size() % 8 != 0)
    {
        buffer.AppendBits(0, 1);
    }

    std::vector<unsigned char> data;
    for (int i = 0; i < buffer.Size(); i += 8)
    {
        unsigned char value = 0;
        for (int j = 0; j < 8; ++j)
        {
            value = static_cast<unsigned char>((value << 1) | (buffer.Get(i + j) ? 1 : 0));
        }
        data.push_back(value);
    }

    for (unsigned char pad = 0xEC; data.size() < DATA_CODEWORDS; pad ^= 0xEC ^ 0x11)
    {
        data.push_back(pad);
    }

    return data;
}

std::vector<unsigned char> QrCode::AddEcc(const std::vector<unsigned char>& data)
{
    std::vector<unsigned char> result = data;
    const std::vector<unsigned char> generator = ReedSolomonGenerator(ECC_CODEWORDS);
    const std::vector<unsigned char> ecc = ReedSolomonRemainder(data, generator);
    result.insert(result.end(), ecc.begin(), ecc.end());
    return result;
}

std::vector<unsigned char> QrCode::ReedSolomonGenerator(int degree)
{
    std::vector<unsigned char> result(degree, 0);
    result[degree - 1] = 1;
    unsigned char root = 1;
    for (int i = 0; i < degree; ++i)
    {
        for (int j = 0; j < degree; ++j)
        {
            result[j] = GaloisMultiply(result[j], root);
            if (j + 1 < degree)
            {
                result[j] = static_cast<unsigned char>(result[j] ^ result[j + 1]);
            }
        }
        root = GaloisMultiply(root, 0x02);
    }
    return result;
}

std::vector<unsigned char> QrCode::ReedSolomonRemainder(
    const std::vector<unsigned char>& data,
    const std::vector<unsigned char>& generator
)
{
    std::vector<unsigned char> result(generator.size(), 0);
    for (unsigned char b : data)
    {
        const unsigned char factor = static_cast<unsigned char>(b ^ result[0]);
        result.erase(result.begin());
        result.push_back(0);

        for (size_t i = 0; i < result.size(); ++i)
        {
            result[i] = static_cast<unsigned char>(result[i] ^ GaloisMultiply(generator[i], factor));
        }
    }
    return result;
}

unsigned char QrCode::GaloisMultiply(unsigned char x, unsigned char y)
{
    int z = 0;
    for (int i = 7; i >= 0; --i)
    {
        z = (z << 1) ^ ((z >> 7) * 0x11D);
        z ^= ((y >> i) & 1) * x;
    }
    return static_cast<unsigned char>(z);
}

int QrCode::GetFormatBits()
{
    int data = 0x08; // Error correction L, mask 0.
    int rem = data;
    for (int i = 0; i < 10; ++i)
    {
        rem = (rem << 1) ^ (((rem >> 9) & 1) * 0x537);
    }
    return ((data << 10) | rem) ^ 0x5412;
}
